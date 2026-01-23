package com.ureca.billing.notification.consumer.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.core.dto.BillingMessageDto;  
import com.ureca.billing.notification.domain.dto.QuietTimeCheckResult;
import com.ureca.billing.notification.domain.entity.Notification;
import com.ureca.billing.notification.domain.repository.NotificationRepository;
import com.ureca.billing.notification.service.EmailService;
import com.ureca.billing.notification.service.UserQuietTimeService;
import com.ureca.billing.notification.service.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 메시지 처리 핸들러 (사용자별 금지 시간대 지원)
 * 
 * 변경 사항:
 * - MessagePolicyService 대신 UserQuietTimeService 사용
 * - 사용자별 금지 시간대 체크 로직 추가
 * - 시스템 정책 + 사용자 설정 통합 처리
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MessageHandler{
    
    private final UserQuietTimeService quietTimeService;
    private final WaitingQueueService queueService;
    private final EmailService emailService;
    private final DuplicateCheckHandler duplicateCheckHandler;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;
    
    @Transactional
    public void handleMessage(String messageJson) {
        try {
            // 1. JSON → DTO 변환
            BillingMessageDto message = objectMapper.readValue(messageJson, BillingMessageDto.class);
            log.info("📨 Received message. billId={}, userId={}, email={}", 
                    message.getBillId(), message.getUserId(), message.getRecipientEmail());
            
            // 2. 중복 체크
            if (duplicateCheckHandler.isDuplicate(message.getBillId())) {
                log.warn("⚠️ Duplicate message skipped. billId={}", message.getBillId());
                saveNotification(message, "FAILED", "Duplicate message", null);
                return;
            }
            
            // 3. 금지 시간 체크 (사용자별 + 시스템 통합)
            QuietTimeCheckResult quietCheck = quietTimeService.checkQuietTime(
                    message.getUserId(), 
                    "EMAIL"
            );
            
            log.info("🕐 Quiet time check result. userId={}, isQuiet={}, reason={}, source={}", 
                    message.getUserId(), quietCheck.isQuietTime(), 
                    quietCheck.getReason(), quietCheck.getSource());
            
            if (quietCheck.isQuietTime()) {
                // 금지 시간 → 대기열 저장
                handleQuietTime(message, quietCheck);
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
     * 금지 시간대 처리
     */
    private void handleQuietTime(BillingMessageDto message, QuietTimeCheckResult quietCheck) {
        String reason = quietCheck.getReason();
        
        switch (reason) {
            case "CHANNEL_DISABLED":
                log.info("🚫 Channel disabled. Notification saved as PENDING. userId={}", 
                        message.getUserId());
                saveNotification(message, "PENDING", "Channel disabled by user", quietCheck);
                break;
                
            case "USER_QUIET_TIME":
                queueService.addToQueue(message);
                saveNotification(message, "PENDING", 
                        "User quiet time: " + quietCheck.getQuietPeriod(), quietCheck);
                log.info("⏰ Added to queue (user quiet time). userId={}, period={}", 
                        message.getUserId(), quietCheck.getQuietPeriod());
                break;
                
            case "SYSTEM_POLICY":
                queueService.addToQueue(message);
                saveNotification(message, "PENDING", "System quiet time (22:00~08:00)", quietCheck);
                log.info("🏢 Added to queue (system policy). userId={}", message.getUserId());
                break;
                
            default:
                log.warn("⚠️ Unknown quiet reason: {}", reason);
                queueService.addToQueue(message);
                saveNotification(message, "PENDING", "Unknown reason: " + reason, quietCheck);
        }
    }
    
    /**
     * 이메일 발송 및 저장
     */
    private void sendEmail(BillingMessageDto message) {
        try {
        	// ✅ 첫 시도 (deliveryAttempt = 1, 1% 실패율 적용)
            emailService.sendEmail(message, 1);
            duplicateCheckHandler.markAsSent(message.getBillId());
            saveNotification(message, "SENT", null, null);
            
            log.info("✅ Email sent successfully. billId={}, userId={}", 
                    message.getBillId(), message.getUserId());
            
        } catch (Exception e) {
            log.error("❌ Email send failed. billId={}, error={}", 
                    message.getBillId(), e.getMessage());
            saveNotification(message, "FAILED", e.getMessage(), null);
            throw new RuntimeException("Email send failed", e);
        }
    }
    
    /**
     * Notification 테이블 저장
     */
    private void saveNotification(BillingMessageDto message, String status, 
                                   String errorMessage, QuietTimeCheckResult quietCheck) {
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
        
        if (quietCheck != null && quietCheck.isQuietTime()) {
            content += String.format("\n[금지 시간] %s (%s)", 
                    quietCheck.getReason(), quietCheck.getSource());
        }
        
        LocalDateTime scheduledAt = LocalDateTime.now();
        if (quietCheck != null && quietCheck.isQuietTime() && quietCheck.getQuietEnd() != null) {
            scheduledAt = LocalDateTime.now()
                    .withHour(quietCheck.getQuietEnd().getHour())
                    .withMinute(quietCheck.getQuietEnd().getMinute());
            
            if (scheduledAt.isBefore(LocalDateTime.now())) {
                scheduledAt = scheduledAt.plusDays(1);
            }
        }
        
        Notification notification = Notification.builder()
                .userId(message.getUserId())
                .notificationType("EMAIL")
                .notificationStatus(status)
                .recipient(message.getRecipientEmail())
                .content(content)
                .retryCount(0)
                .scheduledAt(scheduledAt)
                .sentAt(status.equals("SENT") ? LocalDateTime.now() : null)
                .errorMessage(errorMessage)
                .createdAt(LocalDateTime.now())
                .build();
        
        notificationRepository.save(notification);
        log.debug("💾 Notification saved. billId={}, status={}, scheduledAt={}", 
                message.getBillId(), status, scheduledAt);
    }
}