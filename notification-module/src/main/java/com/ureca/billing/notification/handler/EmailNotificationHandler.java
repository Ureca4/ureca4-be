package com.ureca.billing.notification.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.consumer.handler.DuplicateCheckHandler;
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

/**
 * Email Notification Handler
 * - 이메일 발송 처리
 * - 중복 체크, 금지 시간대 관리
 */
@Component("emailNotificationHandler")
@RequiredArgsConstructor
@Slf4j
public class EmailNotificationHandler implements NotificationHandler {
    
    private final MessagePolicyService policyService;
    private final WaitingQueueService queueService;
    private final EmailService emailService;
    private final DuplicateCheckHandler duplicateCheckHandler;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;
    
    @Override
    @Transactional
    public void handle(BillingMessageDto message, String traceId) {
        log.info("{} 📧 EMAIL 핸들러 처리 시작 - billId={}", traceId, message.getBillId());
        
        // 1. 중복 체크 (타입 포함)
        if (duplicateCheckHandler.isDuplicate(message.getBillId(), "EMAIL")) {
            log.warn("{} ⚠️ 중복 메시지 스킵 - billId={}", traceId, message.getBillId());
            saveNotification(message, "FAILED", "Duplicate message", traceId);
            return;
        }
        
        // 2. 금지 시간대 체크
        boolean isBlockTime = policyService.isBlockTime();
        
        if (isBlockTime) {
            log.info("{} ⏰ 금지 시간대 - 대기열 저장 - billId={}", traceId, message.getBillId());
            try {
                String messageJson = objectMapper.writeValueAsString(message);
                queueService.addToQueue(messageJson);
            } catch (Exception e) {
                log.error("{} JSON 변환 실패", traceId, e);
            }
            saveNotification(message, "PENDING", "Added to waiting queue (block time)", traceId);
            return;
        }
        
        // 3. 이메일 발송
        sendEmail(message, traceId);
    }
    
    
    @Override
    public String getType() {
        return "EMAIL";
    }
    
    private void sendEmail(BillingMessageDto message, String traceId) {
        try {
            // 발송 시도
            emailService.sendEmail(message);
            
            // 발송 완료 마킹
            duplicateCheckHandler.markAsSent(message.getBillId(), "EMAIL");
            
            // DB 저장
            saveNotification(message, "SENT", null, traceId);
            
            log.info("{} ✅ EMAIL 발송 성공 - billId={}", traceId, message.getBillId());
            
        } catch (Exception e) {
            log.error("{} ❌ EMAIL 발송 실패 - billId={}, error={}", 
                traceId, message.getBillId(), e.getMessage());
            
            // 실패 저장
            saveNotification(message, "FAILED", e.getMessage(), traceId);
            
            // 예외 재발생 → Kafka 재시도 또는 DLT
            throw new RuntimeException("Email send failed", e);
        }
    }
    
    private void saveNotification(BillingMessageDto message, String status, String errorMessage, String traceId) {
        String content = createEmailContent(message);
        
        Notification notification = Notification.builder()
            .userId(message.getUserId())
            .notificationType("EMAIL")
            .notificationStatus(status)
            .recipient(message.getRecipientEmail())
            .content(content)
            .retryCount(0)
            .scheduledAt(LocalDateTime.now())
            .sentAt("SENT".equals(status) ? LocalDateTime.now() : null)
            .errorMessage(errorMessage)
            .createdAt(LocalDateTime.now())
            .build();
        
        notificationRepository.save(notification);
        log.debug("{} 💾 Notification 저장 완료 - status={}", traceId, status);
    }
    
    private String createEmailContent(BillingMessageDto message) {
        return String.format(
            "[LG U+ 청구 알림]\n" +
            "청구 년월: %s\n" +
            "총 청구 금액: %,d원\n" +
            "납부 기한: %s\n" +
            "청구일: %s",
            message.getBillYearMonth(),
            message.getTotalAmount() != null ? message.getTotalAmount() : 0,
            message.getDueDate() != null ? message.getDueDate() : "미정",
            message.getBillDate() != null ? message.getBillDate() : "미정"
        );
    }
}