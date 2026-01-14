package com.ureca.billing.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.notification.domain.dto.BillingMessage;
import com.ureca.billing.notification.domain.entity.Notification;
import com.ureca.billing.notification.domain.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RetryService {
    
    private static final String TOPIC = "billing-event";
    private static final int MAX_RETRY_COUNT = 3;
    
    private final NotificationRepository notificationRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    /**
     * FAILED 메시지 재시도
     */
    @Transactional
    public int retryFailedMessages(int limit) {
        log.info("🔍 [DEBUG] Starting to query FAILED messages...");
        
        // 1. FAILED 상태이면서 재시도 가능한 메시지 조회
        List<Notification> allFailedMessages = notificationRepository
            .findFailedMessagesForRetry();
        
        log.info("🔍 [DEBUG] Query returned {} messages", 
            allFailedMessages != null ? allFailedMessages.size() : "null");
        
        if (allFailedMessages != null && !allFailedMessages.isEmpty()) {
            for (Notification n : allFailedMessages) {
                log.info("🔍 [DEBUG] Found notification: id={}, status={}, retryCount={}", 
                    n.getNotificationId(), n.getNotificationStatus(), n.getRetryCount());
            }
        }
        
        // Java에서 limit 적용
        List<Notification> failedMessages = allFailedMessages != null 
            ? allFailedMessages.stream().limit(limit).toList()
            : List.of();
        
        if (failedMessages.isEmpty()) {
            log.info("📭 No failed messages to retry");
            return 0;
        }
        
        log.info("📬 Found {} failed messages to retry (total: {})", 
            failedMessages.size(), allFailedMessages.size());
        
        int successCount = 0;
        int skipCount = 0;
        
        for (Notification notification : failedMessages) {
            try {
                // 2. 재시도 횟수 체크
                if (notification.getRetryCount() >= MAX_RETRY_COUNT) {
                    log.warn("⚠️ Max retry count reached. notificationId={}", 
                        notification.getNotificationId());
                    skipCount++;
                    continue;
                }
                
                // 3. BillingMessage 재구성
                BillingMessage message = reconstructMessage(notification);
                
                // 4. Kafka로 재발행
                String messageJson = objectMapper.writeValueAsString(message);
                kafkaTemplate.send(TOPIC, messageJson);
                
                // 5. 재시도 카운트 증가 및 상태 업데이트
                Notification updatedNotification = notification.incrementRetryCount();
                notificationRepository.save(updatedNotification);
                
                successCount++;
                log.info("🔄 Retry message re-published. notificationId={}, retryCount={}", 
                    notification.getNotificationId(), 
                    updatedNotification.getRetryCount());
                
            } catch (Exception e) {
                log.error("❌ Failed to retry message. notificationId={}, error={}", 
                    notification.getNotificationId(), e.getMessage());
                
                // 3회 실패 시 최종 실패 처리
                if (notification.getRetryCount() + 1 >= MAX_RETRY_COUNT) {
                    Notification finalFailure = notification.markAsFinalFailure(e.getMessage());
                    notificationRepository.save(finalFailure);
                    log.error("💀 Final failure. notificationId={}", 
                        notification.getNotificationId());
                }
            }
        }
        
        log.info("🎯 Retry completed. success={}, skipped={}", successCount, skipCount);
        return successCount;
    }

    private BillingMessage reconstructMessage(Notification notification) {
        // 임시: 간단한 재구성
        return BillingMessage.builder()
            .billId(notification.getNotificationId())  
            .userId(notification.getUserId())
            .recipientEmail(notification.getRecipient())
            .recipientPhone(null)
            .totalAmount(50000)  // 임시 값
            .billYearMonth("2025-01")
            .billDate("2025-01-25")
            .dueDate("2025-02-10")
            .build();
    }
}