package com.ureca.billing.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.domain.entity.UserNotificationPref;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 예약 발송 대기열 서비스
 * 
 * 사용자의 선호 발송 시간에 맞춰 청구서를 예약 발송
 * 
 * Redis Sorted Set 구조:
 * - Key: scheduled:billing:{channel}
 * - Score: 예약 시간 (epoch seconds)
 * - Value: BillingMessageDto JSON
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledQueueService {
    
    private static final String QUEUE_KEY_PREFIX = "scheduled:billing:";
    private static final String ALL_QUEUE_KEY = "scheduled:billing:ALL";
    
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserQuietTimeService userQuietTimeService;
    
    // ========================================
    // 예약 등록 (사용자 선호 시간 기반)
    // ========================================
    
    /**
     * 청구서 예약 등록
     * - 사용자의 선호 발송 시간이 설정되어 있으면 해당 시간에 예약
     * - 설정되어 있지 않으면 null 반환 (즉시 발송 처리해야 함)
     * 
     * @param message 청구 메시지
     * @param channel 발송 채널
     * @return 예약 시간 (null이면 즉시 발송)
     */
    public LocalDateTime scheduleIfPreferred(BillingMessageDto message, String channel) {
        Long userId = message.getUserId();
        
        // 1. 사용자의 선호 발송 시간 조회
        Optional<UserNotificationPref> prefOpt = userQuietTimeService.getUserPref(userId, channel);
        
        if (prefOpt.isEmpty() || !prefOpt.get().hasPreferredSchedule()) {
            log.debug("📨 No preferred schedule for user. userId={}, channel={} → 즉시 발송", userId, channel);
            return null;  // 즉시 발송 처리
        }
        
        UserNotificationPref pref = prefOpt.get();
        
        // 2. 청구 월 파싱 (예: "202501" → 2025-01)
        YearMonth billingMonth = parseBillingMonth(message.getBillYearMonth());
        
        // 3. 다음 발송 예정 시간 계산
        LocalDateTime scheduledAt = pref.getNextScheduledTime(billingMonth);
        
        if (scheduledAt == null) {
            log.warn("⚠️ Failed to calculate scheduled time. userId={}", userId);
            return null;
        }
        
        // 4. 예약 시간이 이미 지났으면 즉시 발송
        if (scheduledAt.isBefore(LocalDateTime.now())) {
            log.info("⏰ Scheduled time already passed. userId={}, scheduledAt={} → 즉시 발송", 
                    userId, scheduledAt);
            return null;
        }
        
        // 5. Redis에 예약 등록
        addToQueue(message, scheduledAt, channel);
        
        log.info("📅 Billing scheduled. userId={}, billId={}, channel={}, scheduledAt={}", 
                userId, message.getBillId(), channel, scheduledAt);
        
        return scheduledAt;
    }
    
    /**
     * 특정 시간으로 예약 등록 (관리자용/테스트용)
     */
    public void schedule(BillingMessageDto message, LocalDateTime scheduledAt, String channel) {
        addToQueue(message, scheduledAt, channel);
        log.info("📅 Manual schedule registered. billId={}, scheduledAt={}", 
                message.getBillId(), scheduledAt);
    }
    
    // ========================================
    // 예약 조회
    // ========================================
    
    /**
     * 발송 시간이 도래한 메시지 조회
     */
    public List<BillingMessageDto> getReadyMessages(String channel, int limit) {
        // 현재 시간을 epoch seconds로 변환 (UTC 기준)
        long now = LocalDateTime.now()
                .atZone(ZoneId.systemDefault())
                .toEpochSecond();
        
        String queueKey = "ALL".equals(channel) ? ALL_QUEUE_KEY : QUEUE_KEY_PREFIX + channel;
        
        log.debug("🔍 [READY CHECK] now={}, queueKey={}", now, queueKey);
        
        Set<String> messages = redisTemplate.opsForZSet()
                .rangeByScore(queueKey, 0, now, 0, limit);
        
        if (messages == null || messages.isEmpty()) {
            log.debug("📭 No messages found with score <= {}", now);
            return Collections.emptyList();
        }
        
        log.info("📤 Found {} ready messages in queue", messages.size());
        
        return messages.stream()
                .map(this::parseMessage)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
    
    /**
     * 특정 사용자의 예약 목록 조회
     */
    public List<Map<String, Object>> getUserSchedules(Long userId) {
        Set<ZSetOperations.TypedTuple<String>> tuples = 
                redisTemplate.opsForZSet().rangeWithScores(ALL_QUEUE_KEY, 0, -1);
        
        if (tuples == null) {
            return Collections.emptyList();
        }
        
        return tuples.stream()
                .map(t -> {
                    BillingMessageDto msg = parseMessage(t.getValue());
                    if (msg != null && userId.equals(msg.getUserId())) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("billId", msg.getBillId());
                        item.put("userId", msg.getUserId());
                        item.put("billYearMonth", msg.getBillYearMonth());
                        item.put("totalAmount", msg.getTotalAmount());
                        item.put("scheduledAt", epochToDateTime(t.getScore()));
                        return item;
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
    
    // ========================================
    // 예약 처리/삭제
    // ========================================
    
    /**
     * 예약 메시지 처리 완료 후 큐에서 제거
     */
    public void markAsProcessed(BillingMessageDto message, String channel) {
        try {
            String messageJson = objectMapper.writeValueAsString(message);
            
            // 채널별 큐에서 제거
            redisTemplate.opsForZSet().remove(QUEUE_KEY_PREFIX + channel, messageJson);
            
            // 전체 큐에서 제거
            redisTemplate.opsForZSet().remove(ALL_QUEUE_KEY, messageJson);
            
            log.debug("✅ Scheduled message processed. billId={}", message.getBillId());
            
        } catch (JsonProcessingException e) {
            log.error("❌ Failed to remove processed message", e);
        }
    }
    
    /**
     * 특정 청구서의 예약 취소
     */
    public boolean cancelByBillId(Long billId, String channel) {
        String queueKey = QUEUE_KEY_PREFIX + channel;
        
        Set<String> allMessages = redisTemplate.opsForZSet().range(queueKey, 0, -1);
        if (allMessages == null) {
            return false;
        }
        
        for (String json : allMessages) {
            BillingMessageDto msg = parseMessage(json);
            if (msg != null && billId.equals(msg.getBillId())) {
                redisTemplate.opsForZSet().remove(queueKey, json);
                redisTemplate.opsForZSet().remove(ALL_QUEUE_KEY, json);
                log.info("🚫 Schedule cancelled. billId={}", billId);
                return true;
            }
        }
        
        log.warn("⚠️ Schedule not found for billId={}", billId);
        return false;
    }
    
    /**
     * 사용자의 모든 예약 취소
     */
    public int cancelByUserId(Long userId) {
        Set<String> allMessages = redisTemplate.opsForZSet().range(ALL_QUEUE_KEY, 0, -1);
        if (allMessages == null) {
            return 0;
        }
        
        int cancelled = 0;
        for (String json : allMessages) {
            BillingMessageDto msg = parseMessage(json);
            if (msg != null && userId.equals(msg.getUserId())) {
                for (String channel : Arrays.asList("EMAIL", "SMS", "PUSH")) {
                    redisTemplate.opsForZSet().remove(QUEUE_KEY_PREFIX + channel, json);
                }
                redisTemplate.opsForZSet().remove(ALL_QUEUE_KEY, json);
                cancelled++;
            }
        }
        
        log.info("🚫 Cancelled {} schedules for userId={}", cancelled, userId);
        return cancelled;
    }
    /**
     * 디버깅용: 큐의 모든 메시지와 score 출력
     */
    public void debugPrintQueue(String channel) {
        String queueKey = "ALL".equals(channel) ? ALL_QUEUE_KEY : QUEUE_KEY_PREFIX + channel;
        
        Set<ZSetOperations.TypedTuple<String>> tuples = 
                redisTemplate.opsForZSet().rangeWithScores(queueKey, 0, -1);
        
        if (tuples == null || tuples.isEmpty()) {
            log.info("🔍 [DEBUG] Queue is empty. key={}", queueKey);
            return;
        }
        
        long now = LocalDateTime.now()
                .atZone(ZoneId.systemDefault())
                .toEpochSecond();
        
        log.info("🔍 [DEBUG] Current epoch: {}, Current time: {}", 
                now, LocalDateTime.now());
        
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            BillingMessageDto msg = parseMessage(tuple.getValue());
            Double score = tuple.getScore();
            
            if (msg != null && score != null) {
                LocalDateTime scheduledAt = epochToDateTime(score);
                boolean isReady = score <= now;
                
                log.info("🔍 [DEBUG] billId={}, score={}, scheduledAt={}, isReady={}", 
                        msg.getBillId(), score, scheduledAt, isReady);
            }
        }
    }
    
    // ========================================
    // 예약 시간 변경
    // ========================================
    
    /**
     * 특정 청구서의 예약 시간 변경
     */
    public boolean reschedule(Long billId, String channel, LocalDateTime newScheduledAt) {
        String queueKey = QUEUE_KEY_PREFIX + channel;
        
        Set<String> allMessages = redisTemplate.opsForZSet().range(queueKey, 0, -1);
        if (allMessages == null) {
            return false;
        }
        
        for (String json : allMessages) {
            BillingMessageDto msg = parseMessage(json);
            if (msg != null && billId.equals(msg.getBillId())) {
                // 기존 예약 삭제
                redisTemplate.opsForZSet().remove(queueKey, json);
                redisTemplate.opsForZSet().remove(ALL_QUEUE_KEY, json);
                
                // 새 시간으로 재등록
                long newEpoch = newScheduledAt.atZone(ZoneId.systemDefault()).toEpochSecond();
                redisTemplate.opsForZSet().add(queueKey, json, newEpoch);
                redisTemplate.opsForZSet().add(ALL_QUEUE_KEY, json, newEpoch);
                
                log.info("📅 Rescheduled. billId={}, newTime={}", billId, newScheduledAt);
                return true;
            }
        }
        
        return false;
    }
    
    // ========================================
    // 통계/모니터링
    // ========================================
    
    /**
     * 채널별 예약 건수
     */
    public Map<String, Long> getQueueStats() {
        Map<String, Long> stats = new HashMap<>();
        
        for (String channel : Arrays.asList("EMAIL", "SMS", "PUSH", "ALL")) {
            String key = "ALL".equals(channel) ? ALL_QUEUE_KEY : QUEUE_KEY_PREFIX + channel;
            Long size = redisTemplate.opsForZSet().size(key);
            stats.put(channel, size != null ? size : 0);
        }
        
        // 발송 대기 중인 메시지 (현재 시간 이전)
        long now = System.currentTimeMillis() / 1000;
        Long readyCount = redisTemplate.opsForZSet().count(ALL_QUEUE_KEY, 0, now);
        stats.put("READY", readyCount != null ? readyCount : 0);
        
        return stats;
    }
    
    /**
     * 전체 예약 목록 조회 (페이징)
     */
    public List<Map<String, Object>> getAllSchedules(int offset, int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .rangeWithScores(ALL_QUEUE_KEY, offset, offset + limit - 1);
        
        if (tuples == null) {
            return Collections.emptyList();
        }
        
        return tuples.stream()
                .map(t -> {
                    BillingMessageDto msg = parseMessage(t.getValue());
                    if (msg != null) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("billId", msg.getBillId());
                        item.put("userId", msg.getUserId());
                        item.put("billYearMonth", msg.getBillYearMonth());
                        item.put("totalAmount", msg.getTotalAmount());
                        item.put("recipientEmail", msg.getRecipientEmail());
                        item.put("scheduledAt", epochToDateTime(t.getScore()));
                        return item;
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
    
    /**
     * 큐 전체 삭제 (테스트용)
     */
    public void clearAll() {
        Set<String> keys = redisTemplate.keys("scheduled:billing:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("🗑️ All scheduled queues cleared. count={}", keys.size());
        }
    }
    
    // ========================================
    // Private Helper
    // ========================================
    
    private void addToQueue(BillingMessageDto message, LocalDateTime scheduledAt, String channel) {
        try {
            String messageJson = objectMapper.writeValueAsString(message);
            long epochSeconds = scheduledAt.atZone(ZoneId.systemDefault()).toEpochSecond();
            
            // 채널별 큐에 저장
            String queueKey = QUEUE_KEY_PREFIX + channel;
            redisTemplate.opsForZSet().add(queueKey, messageJson, epochSeconds);
            
            // 전체 큐에도 저장
            redisTemplate.opsForZSet().add(ALL_QUEUE_KEY, messageJson, epochSeconds);
            
        } catch (JsonProcessingException e) {
            log.error("❌ Failed to serialize message", e);
            throw new RuntimeException("Failed to schedule message", e);
        }
    }
    
    private BillingMessageDto parseMessage(String json) {
        try {
            return objectMapper.readValue(json, BillingMessageDto.class);
        } catch (JsonProcessingException e) {
            log.error("❌ Failed to parse message: {}", e.getMessage());
            return null;
        }
    }
    
    private YearMonth parseBillingMonth(String billYearMonth) {
        if (billYearMonth == null || billYearMonth.length() < 6) {
            return YearMonth.now();
        }
        try {
            int year = Integer.parseInt(billYearMonth.substring(0, 4));
            int month = Integer.parseInt(billYearMonth.substring(4, 6));
            return YearMonth.of(year, month);
        } catch (Exception e) {
            return YearMonth.now();
        }
    }
    
    private LocalDateTime epochToDateTime(Double epoch) {
        if (epoch == null) return null;
        return LocalDateTime.ofEpochSecond(epoch.longValue(), 0, 
                ZoneId.systemDefault().getRules().getOffset(java.time.Instant.now()));
    }
}