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
 * Push Notification Handler
 * - Push 알림 발송 처리 (Mocking)
 * - 앱 푸시 알림 시뮬레이션
 */
@Component("pushNotificationHandler")
@RequiredArgsConstructor
@Slf4j
public class PushNotificationHandler implements NotificationHandler {
    
    private final DuplicateCheckHandler duplicateCheckHandler;
    private final NotificationRepository notificationRepository;
    
    @Override
    @Transactional
    public void handle(BillingMessageDto message, String traceId) {
        log.info("{} 🔔 PUSH 핸들러 처리 시작 - billId={}", traceId, message.getBillId());
        
        // 1. 중복 체크 (PUSH용 키)
        if (duplicateCheckHandler.isDuplicate(message.getBillId(), "PUSH")) {
            log.warn("{} ⚠️ 중복 PUSH 스킵 - billId={}", traceId, message.getBillId());
            return;
        }
        
        // 2. PUSH 발송 (Mocking)
        sendPush(message, traceId);
    }
    
    @Override
    public String getType() {
        return "PUSH";
    }
    
    private void sendPush(BillingMessageDto message, String traceId) {
        try {
            // Push는 SMS와 동일하게 실패 처리 안함
            log.info("{} 🔔 [PUSH 발송 시뮬레이션] to: userId={}, billId: {}, amount: {}원", 
                traceId,
                message.getUserId(),
                message.getBillId(),
                message.getTotalAmount() != null ? String.format("%,d", message.getTotalAmount()) : "0"
            );
            
            // FCM/APNs 발송 시뮬레이션
            simulatePushDelivery(message, traceId);
            
            log.info("{} ✅ PUSH 발송 성공 - billId={}", traceId, message.getBillId());
            
        } catch (Exception e) {
            log.error("{} ❌ PUSH 발송 실패 - billId={}, error={}", 
                traceId, message.getBillId(), e.getMessage());
        }
    }
    
    /**
     * Push 발송 시뮬레이션
     * - 실제로는 FCM(Android) 또는 APNs(iOS)로 전송
     */
    private void simulatePushDelivery(BillingMessageDto message, String traceId) {
        // Push 알림 페이로드 구성 시뮬레이션
        String title = "LG U+ 청구 알림";
        String body = String.format(
            "%s 청구액 %,d원이 발생했습니다.",
            message.getBillYearMonth(),
            message.getTotalAmount() != null ? message.getTotalAmount() : 0
        );
        
        log.debug("{} 📲 [Push Payload] title='{}', body='{}', userId={}", 
            traceId, title, body, message.getUserId());
        
        // 실제 환경에서는 여기서 FCM/APNs API 호출
        // FirebaseMessaging.getInstance().send(message);
    }
    
    private void saveNotification(BillingMessageDto message, String status, String errorMessage, String traceId) {
        String content = String.format(
            "[LG U+] %s 청구액 %,d원. 자세한 내용은 앱에서 확인하세요.",
            message.getBillYearMonth(),
            message.getTotalAmount() != null ? message.getTotalAmount() : 0
        );
        
        Notification notification = Notification.builder()
            .userId(message.getUserId())
            .notificationType("PUSH")
            .notificationStatus(status)
            .recipient("userId:" + message.getUserId()) // Push는 userId를 recipient로 저장
            .content(content)
            .retryCount(0)
            .scheduledAt(LocalDateTime.now())
            .sentAt("SENT".equals(status) ? LocalDateTime.now() : null)
            .errorMessage(errorMessage)
            .createdAt(LocalDateTime.now())
            .build();
        
        notificationRepository.save(notification);
        log.debug("{} 💾 PUSH Notification 저장 완료 - status={}", traceId, status);
    }
}