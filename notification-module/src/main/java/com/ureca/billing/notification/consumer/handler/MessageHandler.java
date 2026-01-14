package com.ureca.billing.notification.consumer.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.notification.domain.dto.BillingMessage;
import com.ureca.billing.notification.domain.entity.Notification;
import com.ureca.billing.notification.domain.repository.NotificationRepository;
import com.ureca.billing.notification.service.EmailService;
import com.ureca.billing.notification.service.MessagePolicyService;
import com.ureca.billing.notification.service.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class MessageHandler {
    
    private final MessagePolicyService policyService;
    private final WaitingQueueService queueService;
    private final EmailService emailService;
    private final DuplicateCheckHandler duplicateCheckHandler;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;
    
    @Transactional
    public void handleMessage(String messageJson) {
        try {
            // 1. JSON → DTO 변환
            BillingMessage message = objectMapper.readValue(messageJson, BillingMessage.class);
            log.info("📨 Received message. billId={}, email={}", 
                    message.getBillId(), message.getRecipientEmail());
            
            // 2. 중복 체크
            if (duplicateCheckHandler.isDuplicate(message.getBillId())) {
                log.warn("⚠️ Duplicate message skipped. billId={}", message.getBillId());
                saveNotification(message, "FAILED", "Duplicate message");
                return;
            }
            
            // 3. 금지 시간 체크
            boolean isBlockTime = policyService.isBlockTime();
            
            if (isBlockTime) {
                // 금지 시간 → 대기열 저장
                queueService.addToQueue(message);
                saveNotification(message, "PENDING", "Added to waiting queue");
                log.info("⏰ Message added to queue. billId={}", message.getBillId());
                
            } else {
                // 정상 시간 → 즉시 발송
                sendEmail(message);
            }
            
        } catch (Exception e) {
            log.error("❌ Failed to handle message: {}", e.getMessage(), e);
            throw new RuntimeException("Message handling failed", e);
        }
    }
    
    /**
     * 이메일 발송 및 저장
     */
    private void sendEmail(BillingMessage message) {
        try {
            // 발송
            emailService.sendEmail(message);
            
            // 발송 완료 마킹
            duplicateCheckHandler.markAsSent(message.getBillId());
            
            // DB 저장
            saveNotification(message, "SENT", null);
            
            log.info("✅ Email sent successfully. billId={}", message.getBillId());
            
        } catch (Exception e) {
            log.error("❌ Email send failed. billId={}, error={}", 
                    message.getBillId(), e.getMessage());
            
            // 실패 저장
            saveNotification(message, "FAILED", e.getMessage());
            
        }
    }
    
    /**
     * Notification 테이블 저장
     */
    private void saveNotification(BillingMessage message, String status, String errorMessage) {
        // ✅ content 생성 추가!
        String content = String.format(
            "[LG U+ 청구 알림]\n" +
            "청구 년월: %s\n" +
            "총 청구 금액: %,d원\n" +
            "납부 기한: %s\n" +
            "청구일: %s",
            message.getBillYearMonth(),
            message.getTotalAmount(),
            message.getDueDate() != null ? message.getDueDate() : "미정",
            message.getBillDate() != null ? message.getBillDate() : "미정"
        );
        
        Notification notification = Notification.builder()
                .userId(message.getUserId())
                .notificationType("EMAIL")
                .notificationStatus(status)
                .recipient(message.getRecipientEmail())
                .content(content)  // ← 이 줄 추가!
                .retryCount(0)
                .scheduledAt(LocalDateTime.now())
                .sentAt(status.equals("SENT") ? LocalDateTime.now() : null)
                .errorMessage(errorMessage)
                .createdAt(LocalDateTime.now())
                .build();
        
        notificationRepository.save(notification);
        log.info("💾 Notification saved. billId={}, status={}", message.getBillId(), status);
    }
}