package com.ureca.billing.notification.consumer.handler;

import com.ureca.billing.notification.domain.entity.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.Getter;
import lombok.Builder;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 중복 발송 체크 및 재시도 관리 핸들러
 * 
 * Redis 키 전략:
 * - 발송 완료: sent:msg:{billId}:{type} (예: sent:msg:1002:EMAIL)
 * - 처리 중 (PENDING/SCHEDULED): processing:msg:{billId}:{type}
 * - 재시도: retry:msg:{billId}:{type}
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DuplicateCheckHandler {
    
    private final RedisTemplate<String, String> redisTemplate;
    
    // 발송 완료 키 (중복 방지용)
    private static final String SENT_KEY_PREFIX = "sent:msg:";
    private static final long SENT_TTL_DAYS = 7;
    
    // 처리 중 키 (PENDING/SCHEDULED 상태 - 중복 방지용)
    private static final String PROCESSING_KEY_PREFIX = "processing:msg:";
    private static final long PROCESSING_TTL_DAYS = 3;  // 3일 후 자동 만료
    
    // 재시도 키
    private static final String RETRY_KEY_PREFIX = "retry:msg:";
    private static final long RETRY_TTL_HOURS = 1;
    
    // ========================================
    // CheckResult 내부 클래스
    // ========================================
    
    /**
     * 메시지 상태 체크 결과
     */
    @Getter
    @Builder
    public static class CheckResult {
        private final boolean duplicate;      // 이미 발송된 메시지인지
        private final boolean retry;          // 재시도 메시지인지
        private final Long notificationId;    // 재시도 시 기존 Notification ID
        
        public boolean isDuplicate() {
            return duplicate;
        }
        
        public boolean isRetry() {
            return retry;
        }
    }
    
    // ========================================
    // 1. 통합 메시지 상태 체크
    // ========================================
    
    /**
     * 메시지 상태 통합 체크 (중복 + 재시도)
     * 
     * @param billId 청구서 ID
     * @return CheckResult (중복 여부, 재시도 여부, 기존 notificationId)
     */
    public CheckResult checkMessageStatus(Long billId) {
        return checkMessageStatus(billId, "EMAIL");
    }
    
    /**
     * 메시지 상태 통합 체크 (타입별)
     */
    public CheckResult checkMessageStatus(Long billId, String notificationType) {
        // 1. 중복 체크 (이미 발송 완료된 메시지인지)
        if (isDuplicate(billId, notificationType)) {
            return CheckResult.builder()
                    .duplicate(true)
                    .retry(false)
                    .notificationId(null)
                    .build();
        }
        
        // 2. 중복 체크 - 처리 중인 메시지 (PENDING/SCHEDULED)
        if (isProcessing(billId, notificationType)) {
            log.debug("⏳ [중복 체크] 처리 중인 메시지입니다. billId={}, type={}", billId, notificationType);
            return CheckResult.builder()
                    .duplicate(true)
                    .retry(false)
                    .notificationId(null)
                    .build();
        }
        
        // 3. 재시도 메시지인지 확인
        Long existingNotificationId = getRetryNotificationId(billId, notificationType);
        boolean isRetry = existingNotificationId != null;
        
        return CheckResult.builder()
                .duplicate(false)
                .retry(isRetry)
                .notificationId(existingNotificationId)
                .build();
    }
    
    // ========================================
    // 2. 중복 발송 체크
    // ========================================
    
    /**
     * 중복 발송 체크 (타입별)
     * Redis 키: sent:msg:{billId}:{type}
     * 
     * @param billId 청구서 ID
     * @param notificationType 알림 타입 (EMAIL, SMS)
     * @return true면 이미 발송된 메시지
     */
    public boolean isDuplicate(Long billId, String notificationType) {
        String key = buildSentKey(billId, notificationType);
        Boolean exists = redisTemplate.hasKey(key);
        
        if (Boolean.TRUE.equals(exists)) {
            //log.warn("⚠️ [중복 체크] 이미 발송된 메시지입니다. billId={}, type={}, key={}",
            //        billId, notificationType, key);
            return true;
        }
        
        return false;
    }
    
    /**
     * 중복 발송 체크 (기본 EMAIL)
     */
    public boolean isDuplicate(Long billId) {
        return isDuplicate(billId, "EMAIL");
    }
    
    /**
     * 발송 완료 마킹 (타입별)
     * Redis 키: sent:msg:{billId}:{type} = "sent"
     * TTL: 7일
     */
    public void markAsSent(Long billId, String notificationType) {
        String key = buildSentKey(billId, notificationType);
        redisTemplate.opsForValue().set(key, "sent", SENT_TTL_DAYS, TimeUnit.DAYS);
        //log.info("✅ [발송 완료] Redis에 발송 완료 마킹. billId={}, type={}, key={}, TTL={}days",
                //billId, notificationType, key, SENT_TTL_DAYS);
    }
    
    /**
     * 발송 완료 마킹 (기본 EMAIL)
     */
    public void markAsSent(Long billId) {
        markAsSent(billId, "EMAIL");
    }
    
 // ========================================
    // 3. 처리 중 상태 관리 (PENDING/SCHEDULED)
    // ========================================
    
    /**
     * 처리 중인 메시지인지 체크
     * Redis 키: processing:msg:{billId}:{type}
     */
    public boolean isProcessing(Long billId, String notificationType) {
        String key = buildProcessingKey(billId, notificationType);
        Boolean exists = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }
    
    /**
     * 처리 중 마킹 (PENDING/SCHEDULED 상태)
     * Redis 키: processing:msg:{billId}:{type} = "processing"
     * TTL: 3일
     */
    public void markAsProcessing(Long billId, String notificationType) {
        String key = buildProcessingKey(billId, notificationType);
        redisTemplate.opsForValue().set(key, "processing", PROCESSING_TTL_DAYS, TimeUnit.DAYS);
        log.debug("⏳ [처리 중 마킹] billId={}, type={}, TTL={}days", billId, notificationType, PROCESSING_TTL_DAYS);
    }
    
    /**
     * 처리 중 마킹 (기본 EMAIL)
     */
    public void markAsProcessing(Long billId) {
        markAsProcessing(billId, "EMAIL");
    }
    
    /**
     * 처리 중 키 삭제 (발송 완료 시)
     */
    public void removeProcessingKey(Long billId, String notificationType) {
        String key = buildProcessingKey(billId, notificationType);
        redisTemplate.delete(key);
    }
    
    // ========================================
    // 4. 재시도 메시지 관리
    // ========================================
    
    /**
     * 재시도 메시지인지 확인
     */
    public boolean isRetryMessage(Long billId, String notificationType) {
        String key = buildRetryKey(billId, notificationType);
        Boolean exists = redisTemplate.hasKey(key);
        
        if (Boolean.TRUE.equals(exists)) {
            //log.info("🔄 [재시도 체크] 재시도 메시지입니다. billId={}, type={}, key={}",
            //        billId, notificationType, key);
            return true;
        }
        
        //log.debug("📨 [재시도 체크] 신규 메시지입니다. billId={}, type={}", billId, notificationType);
        return false;
    }
    
    /**
     * 재시도 메시지의 기존 Notification ID 조회
     */
    public Long getRetryNotificationId(Long billId, String notificationType) {
        String key = buildRetryKey(billId, notificationType);
        String value = redisTemplate.opsForValue().get(key);
        
        if (value != null) {
            try {
                Long notificationId = Long.parseLong(value);
                //log.info("🔍 [재시도 조회] 기존 Notification 발견. billId={}, type={}, notificationId={}",
                //        billId, notificationType, notificationId);
                return notificationId;
            } catch (NumberFormatException e) {
                log.warn("⚠️ [재시도 조회] notificationId 파싱 실패. billId={}, type={}, value={}",
                        billId, notificationType, value);
            }
        }
        
        return null;
    }
    
    /**
     * 재시도 정보 Redis에 저장 (타입별)
     */
    public void markAsRetry(Long billId, String notificationType, Long notificationId) {
        String key = buildRetryKey(billId, notificationType);
        redisTemplate.opsForValue().set(
                key, 
                String.valueOf(notificationId), 
                RETRY_TTL_HOURS, 
                TimeUnit.HOURS
        );
        //log.info("🔄 [재시도 저장] Redis에 재시도 정보 저장. billId={}, type={}, notificationId={}, TTL={}hour",
        //        billId, notificationType, notificationId, RETRY_TTL_HOURS);
    }
    
    /**
     * 재시도 정보 Redis에 저장 (기본 EMAIL) - RetryService 호환용
     */
    public void markAsRetry(Long billId, Long notificationId) {
        markAsRetry(billId, "EMAIL", notificationId);
    }
    
    /**
     * 재시도 키 삭제
     */
    public void removeRetryKey(Long billId, String notificationType) {
        String key = buildRetryKey(billId, notificationType);
        Boolean deleted = redisTemplate.delete(key);
        
        if (Boolean.TRUE.equals(deleted)) {
            //log.info("🗑️ [재시도 삭제] Redis 재시도 키 삭제 완료. billId={}, type={}, key={}",
            //        billId, notificationType, key);
        } else {
        	log.debug("🗑️ [재시도 삭제] Redis 재시도 키 삭제 완료. billId={}, type={}", billId, notificationType);
                    
        }
    }
    
    // ========================================
    // 4. 키 생성 Helper
    // ========================================
    
    /**
     * 발송 완료 키 생성: sent:msg:{billId}:{type}
     */
    private String buildSentKey(Long billId, String notificationType) {
        return SENT_KEY_PREFIX + billId + ":" + notificationType;
    }
    
    /**
     * 처리 중 키 생성: processing:msg:{billId}:{type}
     */
    private String buildProcessingKey(Long billId, String notificationType) {
        return PROCESSING_KEY_PREFIX + billId + ":" + notificationType;
    }
    
    /**
     * 재시도 키 생성: retry:msg:{billId}:{type}
     */
    private String buildRetryKey(Long billId, String notificationType) {
        return RETRY_KEY_PREFIX + billId + ":" + notificationType;
    }
    
    // ========================================
    // 5. 발송 성공 처리
    // ========================================
    
    /**
     * 발송 성공 처리 (중복 방지 키 저장 + 재시도 키 삭제 + 처리 중 키 삭제) - 타입별
     */
    public void onSendSuccess(Long billId, String notificationType) {
        markAsSent(billId, notificationType);
        removeRetryKey(billId, notificationType);
        removeProcessingKey(billId, notificationType);
        //log.info("✅ [발송 성공 처리 완료] billId={}, type={}", billId, notificationType);
    }
    
    /**
     * 발송 성공 처리 (기본 EMAIL) - BillingEventConsumer 호환용
     */
    public void onSendSuccess(Long billId) {
        onSendSuccess(billId, "EMAIL");
    }
    
    /**
     * 처리 시작 마킹 (PENDING/SCHEDULED 상태일 때 호출)
     * 중복 처리 방지를 위해 Consumer에서 호출
     */
    public void onProcessingStart(Long billId, String notificationType) {
        markAsProcessing(billId, notificationType);
    }

    public void bulkMarkAsSent(List<Notification> notifications) {
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Notification n : notifications) {
                String key = "sent:msg:" + n.getBillId() + ":" + n.getNotificationType();
                // setEx (key, seconds, value)
                connection.setEx(key.getBytes(), 7 * 24 * 60 * 60, "true".getBytes());
            }
            return null;
        });
    }
}