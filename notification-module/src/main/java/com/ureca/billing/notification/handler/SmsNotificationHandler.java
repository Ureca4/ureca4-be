package com.ureca.billing.notification.handler;

import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.consumer.handler.DuplicateCheckHandler;
import com.ureca.billing.notification.domain.entity.Notification;
import com.ureca.billing.notification.domain.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * SMS Notification Handler
 * - SMS 발송 처리 (Mocking)
 * - 이메일 3회 실패 시 대체 발송
 */
@Component("smsNotificationHandler")
@RequiredArgsConstructor
@Slf4j
public class SmsNotificationHandler implements NotificationHandler {
    
    private final DuplicateCheckHandler duplicateCheckHandler;
    private final NotificationRepository notificationRepository;
    
    @Override
    @Transactional
    public void handle(BillingMessageDto message, String traceId) {
        log.info("{} 📱 SMS 핸들러 처리 시작 - billId={}", traceId, message.getBillId());
        
        // 1. 중복 체크 (SMS용 키)
        String smsKey = "SMS:" + message.getBillId();
        if (duplicateCheckHandler.isDuplicate(message.getBillId(), "SMS")) {
            log.warn("{} ⚠️ 중복 SMS 스킵 - billId={}", traceId, message.getBillId());
            return;
        }
        
        // 2. SMS 발송 (Mocking - 실제 발송 안함)
        sendSms(message, traceId);
    }
    
    @Override
    public String getType() {
        return "SMS";
    }
    
    private void sendSms(BillingMessageDto message, String traceId) {
        try {
            // SMS는 실패 처리 안함 (요구사항)
            log.info("{} 📱 [SMS 발송 시뮬레이션] to: {}, billId: {}, amount: {}원", 
                traceId,
                message.getRecipientPhone(),
                message.getBillId(),
                message.getTotalAmount() != null ? String.format("%,d", message.getTotalAmount()) : "0"
            );
            
            log.info("{} ✅ SMS 발송 성공 - billId={}", traceId, message.getBillId());
            
        } catch (Exception e) {
            log.error("{} ❌ SMS 발송 실패 - billId={}, error={}", 
                traceId, message.getBillId(), e.getMessage());
        }
    }
    
    private void saveNotification(BillingMessageDto message, String status, String errorMessage, String traceId) {
        String content = String.format(
            "[LG U+] %s 청구액 %,d원. 납부기한: %s",
            message.getBillYearMonth(),
            message.getTotalAmount() != null ? message.getTotalAmount() : 0,
            message.getDueDate() != null ? message.getDueDate() : "미정"
        );
        
        Notification notification = Notification.builder()
            .userId(message.getUserId())
            .notificationType("SMS")
            .notificationStatus(status)
            .recipient(message.getRecipientPhone())
            .content(content)
            .retryCount(0)
            .scheduledAt(LocalDateTime.now())
            .sentAt("SENT".equals(status) ? LocalDateTime.now() : null)
            .errorMessage(errorMessage)
            .createdAt(LocalDateTime.now())
            .build();
        
        notificationRepository.save(notification);
        log.debug("{} 💾 SMS Notification 저장 완료 - status={}", traceId, status);
    }
}