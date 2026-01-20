package com.ureca.billing.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.consumer.handler.DuplicateCheckHandler;
import com.ureca.billing.notification.domain.entity.Notification;
import com.ureca.billing.notification.domain.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 재시도 서비스
 * 
 * 아키텍처 플로우 (Retry Scheduler - 5분마다 실행):
 * 1. status = "FAILED" 조회
 * 2. retry_count < 3 인 경우:
 *    - DB 상태 업데이트: status = "RETRY", retry_count++
 *    - Redis에 재시도 정보 저장: key: retry:msg:{billId}, value: notificationId, TTL: 1시간
 *    - Kafka로 재발행 (billing-event-topic)
 *    - 처음 로직으로 돌아감
 * 3. retry_count >= 3 인 경우:
 *    - DLQ로 이동 (billing-event.DLT)
 *    - 관리자 처리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetryService {
    
    private static final String TOPIC = "billing-event";
    private static final int MAX_RETRY_COUNT = 3;
    
    private final NotificationRepository notificationRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final DuplicateCheckHandler duplicateCheckHandler;  // 추가: Redis 재시도 키 관리
    
    /**
     * FAILED 메시지 재시도
     * 
     * 플로우:
     * 1. FAILED 상태 + retry_count < 3 인 메시지 조회
     * 2. 각 메시지에 대해:
     *    - DB 상태 업데이트: status = "RETRY", retry_count++
     *    - Redis에 재시도 정보 저장: retry:msg:{billId} = notificationId (TTL 1시간)
     *    - Kafka로 재발행
     * 3. retry_count >= 3 인 경우 DLQ로 이동
     */
    @Transactional
    public int retryFailedMessages(int limit) {
        log.info("🔄 [RETRY] 재시도 프로세스 시작...");
        
        // 1. FAILED 상태이면서 재시도 가능한 메시지 조회
        List<Notification> allFailedMessages = notificationRepository.findFailedMessagesForRetry();
        
        if (allFailedMessages == null || allFailedMessages.isEmpty()) {
            log.info("📭 [RETRY] 재시도할 메시지 없음");
            return 0;
        }
        
        // Java에서 limit 적용
        List<Notification> failedMessages = allFailedMessages.stream()
            .limit(limit)
            .toList();
        
        log.info("📬 [RETRY] 재시도 대상 메시지 발견. 처리 예정: {}, 전체: {}", 
                failedMessages.size(), allFailedMessages.size());
        
        int successCount = 0;
        int dlqCount = 0;
        
        for (Notification notification : failedMessages) {
            try {
                // 2. 재시도 횟수 체크
                if (notification.getRetryCount() >= MAX_RETRY_COUNT) {
                    // 3회 이상 실패 → DLQ 이동 (실제로는 Kafka ErrorHandler에서 처리됨)
                    log.warn("💀 [RETRY] 최대 재시도 횟수 초과. DLQ 대상. notificationId={}, retryCount={}", 
                            notification.getNotificationId(), notification.getRetryCount());
                    
                    // 최종 실패 상태로 업데이트
                    Notification finalFailure = notification.markAsFinalFailure("Max retry count exceeded");
                    notificationRepository.save(finalFailure);
                    dlqCount++;
                    continue;
                }
                
                // 3. DB 상태 업데이트: status = "RETRY", retry_count++
                Notification updatedNotification = notification.incrementRetryCount();
                notificationRepository.save(updatedNotification);
                
                log.info("📝 [RETRY] DB 상태 업데이트. notificationId={}, status=RETRY, retryCount={}", 
                        notification.getNotificationId(), updatedNotification.getRetryCount());
                
                // 4. BillingMessage 재구성
                BillingMessageDto message = reconstructMessage(notification);
                
                // 5. Redis에 재시도 정보 저장: retry:msg:{billId} = notificationId (TTL 1시간)
                duplicateCheckHandler.markAsRetry(message.getBillId(), notification.getNotificationId());
                
                log.info("💾 [RETRY] Redis에 재시도 정보 저장. billId={}, notificationId={}", 
                        message.getBillId(), notification.getNotificationId());
                
                // 6. Kafka로 재발행
                String messageJson = objectMapper.writeValueAsString(message);
                kafkaTemplate.send(TOPIC, messageJson);
                
                log.info("📤 [RETRY] Kafka로 재발행 완료. billId={}, notificationId={}, retryCount={}", 
                        message.getBillId(), notification.getNotificationId(), updatedNotification.getRetryCount());
                
                successCount++;
                
            } catch (Exception e) {
                log.error("❌ [RETRY] 재시도 처리 실패. notificationId={}, error={}", 
                        notification.getNotificationId(), e.getMessage());
                
                // 예외 발생 시에도 retry_count가 증가되어 있으므로 
                // 다음 스케줄러 실행 시 다시 시도됨
            }
        }
        
        log.info("🎯 [RETRY] 재시도 프로세스 완료. 성공: {}, DLQ: {}, 총 처리: {}", 
                successCount, dlqCount, successCount + dlqCount);
        
        return successCount;
    }

    /**
     * Notification에서 BillingMessageDto 재구성
     * 
     * TODO: 실제 구현에서는 BILLS 테이블을 조회하여 정확한 정보를 가져와야 함
     * 현재는 Notification에 저장된 정보로 최소한의 재구성
     */
    private BillingMessageDto reconstructMessage(Notification notification) {
        // content에서 정보 파싱 시도 (간단한 구현)
        // 실제로는 bill_id를 저장하고 BILLS 테이블을 조회하는 것이 좋음
        
        return BillingMessageDto.builder()
        	.billId(notification.getBillId())  // ✅ notificationId 대신 billId 사용
            .userId(notification.getUserId())
            .recipientEmail(notification.getRecipient())
            .recipientPhone(null)
            .totalAmount(50000L)  // 임시 값 (실제로는 DB에서 조회)
            .billYearMonth("2025-01")
            .billDate("2025-01-25")
            .dueDate("2025-02-10")
            .build();
    }
    
    /**
     * 특정 Notification 수동 재시도
     */
    @Transactional
    public boolean retryNotification(Long notificationId) {
        log.info("🔄 [MANUAL RETRY] 수동 재시도 요청. notificationId={}", notificationId);
        
        return notificationRepository.findById(notificationId)
            .map(notification -> {
                if (notification.getRetryCount() >= MAX_RETRY_COUNT) {
                    log.warn("⚠️ [MANUAL RETRY] 최대 재시도 횟수 초과. notificationId={}", notificationId);
                    return false;
                }
                
                try {
                    // DB 상태 업데이트
                    Notification updated = notification.incrementRetryCount();
                    notificationRepository.save(updated);
                    
                    // BillingMessage 재구성
                    BillingMessageDto message = reconstructMessage(notification);
                    
                    // Redis에 재시도 정보 저장
                    duplicateCheckHandler.markAsRetry(message.getBillId(), notificationId);
                    
                    // Kafka로 재발행
                    String messageJson = objectMapper.writeValueAsString(message);
                    kafkaTemplate.send(TOPIC, messageJson);
                    
                    log.info("✅ [MANUAL RETRY] 수동 재시도 완료. notificationId={}", notificationId);
                    return true;
                    
                } catch (Exception e) {
                    log.error("❌ [MANUAL RETRY] 수동 재시도 실패. notificationId={}, error={}", 
                            notificationId, e.getMessage());
                    return false;
                }
            })
            .orElseGet(() -> {
                log.warn("⚠️ [MANUAL RETRY] Notification을 찾을 수 없음. notificationId={}", notificationId);
                return false;
            });
    }
}