package com.ureca.billing.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.notification.domain.dto.BillingMessage;
import com.ureca.billing.notification.domain.dto.WaitingQueueStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaitingQueueService {
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    
    private static final String QUEUE_KEY = "queue:message:waiting";
    
    /**
     * 대기열에 메시지 추가
     */
    public void addToQueue(BillingMessage message) {
        try {
            // 다음날 08:00 계산
            LocalDateTime releaseTime = calculateReleaseTime();
            long score = releaseTime.atZone(ZoneId.systemDefault()).toEpochSecond();
            
            String messageJson = objectMapper.writeValueAsString(message);
            
            redisTemplate.opsForZSet().add(QUEUE_KEY, messageJson, score);
            
            log.info("📥 Message added to waiting queue. billId={}, releaseTime={}", 
                    message.getBillId(), releaseTime);
            
        } catch (Exception e) {
            log.error("❌ Failed to add message to queue: {}", e.getMessage());
            throw new RuntimeException("Failed to add to queue", e);
        }
    }
    
    /**
     * 발송 가능한 메시지 조회 (현재 시간 이전)
     */
    public Set<String> getReadyMessages(int limit) {
        long now = System.currentTimeMillis() / 1000;
        
        Set<String> messages = redisTemplate.opsForZSet()
                .rangeByScore(QUEUE_KEY, 0, now, 0, limit);
        
        log.info("📤 Found {} ready messages in queue", messages != null ? messages.size() : 0);
        
        return messages;
    }
    
    /**
     * 대기열에서 메시지 제거
     */
    public void removeFromQueue(String messageJson) {
        Long removed = redisTemplate.opsForZSet().remove(QUEUE_KEY, messageJson);
        log.debug("🗑️ Removed {} message(s) from queue", removed);
    }
    /**
     * 대기열 크기 확인
     */
    public long getQueueSize() {
        Long size = redisTemplate.opsForZSet().size(QUEUE_KEY);
        return size != null ? size : 0;
    }

    /**
     * 대기열 전체 삭제 (테스트용)
     */
    public void clearQueue() {
        Boolean deleted = redisTemplate.delete(QUEUE_KEY);
        log.info("🗑️ Waiting queue cleared. deleted={}", deleted);
    }
    /**
     * 대기열 상태 조회
     */
    public WaitingQueueStatus getQueueStatus() {
        Long totalCount = redisTemplate.opsForZSet().size(QUEUE_KEY);
        
        long now = System.currentTimeMillis() / 1000;
        Long readyCount = redisTemplate.opsForZSet().count(QUEUE_KEY, 0, now);
        
        Set<String> readyMessages = getReadyMessages(10);  // 최대 10개만
        
        List<String> messageList = readyMessages != null 
                ? readyMessages.stream().limit(10).collect(Collectors.toList())
                : List.of();
        
        return WaitingQueueStatus.builder()
                .totalCount(totalCount != null ? totalCount : 0)
                .queueKey(QUEUE_KEY)
                .readyCount(readyCount != null ? readyCount : 0)
                .readyMessages(messageList)
                .build();
    }
    
    /**
     * 다음 발송 가능 시간 계산
     * 
     * 테스트 모드: 즉시 발송 가능
     * 운영 모드: 다음날 08:00
     */
    private LocalDateTime calculateReleaseTime() {
        // ✅ 테스트용: 즉시 발송 가능하도록 과거 시간 설정
        //return LocalDateTime.now().minusMinutes(1);
        
        // 🚀 운영용: 다음날 08:00 (배포 시 주석 해제)
         LocalDateTime now = LocalDateTime.now();
         LocalDateTime nextRelease = now.toLocalDate().plusDays(1).atTime(8, 0);
          return nextRelease;
    }
}