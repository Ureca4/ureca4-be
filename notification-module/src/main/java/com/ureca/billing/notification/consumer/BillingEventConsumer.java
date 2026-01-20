package com.ureca.billing.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.consumer.handler.DuplicateCheckHandler;
import com.ureca.billing.notification.consumer.handler.DuplicateCheckHandler.CheckResult;
import com.ureca.billing.notification.domain.entity.Notification;
import com.ureca.billing.notification.domain.repository.NotificationRepository;
import com.ureca.billing.notification.service.EmailService;
import com.ureca.billing.notification.service.MessagePolicyService;
import com.ureca.billing.notification.service.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Kafka 메시지 Consumer
 * 
 * 아키텍처 플로우:
 * 1. Kafka 메시지 수신 (billing-event-topic)
 * 2. 중복 발송? → Redis 조회 키: sent:msg:{billId}
 *    - yes → skip
 *    - no → 재시도 메시지인지 확인
 * 3. 재시도 메시지? → Redis key: retry:msg:{billId} 조회
 *    - 재시도일 경우, 기존 Notification 이용
 *    - 새로운 메시지일 경우, 발송 때 Notification 생성
 * 4. 금지 시간? → Redis WaitingQueue 저장, status = "PENDING"
 * 5. 메일/SMS 발송 시도
 *    - 성공 → status = "SENT", sent:msg:{billId} 저장, retry:msg:{billId} 삭제
 *    - 실패 → status = "FAILED", retry_count = 0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingEventConsumer {

    private final ObjectMapper objectMapper;
    private final DuplicateCheckHandler duplicateCheckHandler;
    private final MessagePolicyService policyService;
    private final WaitingQueueService queueService;
    private final EmailService emailService;
    private final NotificationRepository notificationRepository;

    @KafkaListener(
        topics = "billing-event",
        groupId = "notification-group",
        containerFactory = "kafkaListenerContainerFactory",
        concurrency = "3"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String traceInfo = String.format("[P%d-O%d]", record.partition(), record.offset());
        long startTime = System.currentTimeMillis();

        log.info("{} 📥 메시지 수신", traceInfo);

        try {
            // 1. JSON 파싱
            String messageJson = record.value();
            BillingMessageDto message = objectMapper.readValue(messageJson, BillingMessageDto.class);

            log.info("{} 📨 billId={}, userId={}", traceInfo, message.getBillId(), message.getUserId());

            // 2. 메시지 상태 체크 (중복 + 재시도 통합)
            CheckResult checkResult = duplicateCheckHandler.checkMessageStatus(message.getBillId());
            
            // 2-1. 중복 메시지 → skip
            if (checkResult.isDuplicate()) {
                log.warn("{} ⚠️ 중복 메시지 스킵. billId={}", traceInfo, message.getBillId());
                ack.acknowledge();
                return;
            }
            
            // 2-2. 재시도 메시지 여부 확인
            boolean isRetry = checkResult.isRetry();
            Long existingNotificationId = checkResult.getNotificationId();
            
            if (isRetry) {
                log.info("{} 🔄 재시도 메시지. billId={}, existingNotificationId={}", 
                        traceInfo, message.getBillId(), existingNotificationId);
            } else {
                log.info("{} 📨 신규 메시지. billId={}", traceInfo, message.getBillId());
            }

            // 3. 금지 시간 체크 (22:00 ~ 08:00)
            if (policyService.isBlockTime()) {
                handleBlockTime(message, messageJson, isRetry, existingNotificationId, traceInfo);
                ack.acknowledge();
                return;
            }

            // 4. 이메일 발송
            sendEmail(message, isRetry, existingNotificationId, traceInfo);

            // 5. 수동 커밋
            ack.acknowledge();

            long duration = System.currentTimeMillis() - startTime;
            log.info("{} ✅ 처리 완료 ({}ms)", traceInfo, duration);

        } catch (Exception e) {
            log.error("{} ❌ 처리 실패: {}", traceInfo, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 금지 시간대 처리
     * - 대기열에 메시지 저장
     * - Notification 상태를 PENDING으로 저장
     */
    private void handleBlockTime(BillingMessageDto message, String messageJson, 
                                  boolean isRetry, Long existingNotificationId, String traceInfo) {
        // 대기열에 저장
        queueService.addToQueue(messageJson);
        
        // Notification 저장/업데이트
        if (isRetry && existingNotificationId != null) {
            // 재시도 메시지 → 기존 Notification 상태 업데이트
            updateNotificationStatus(existingNotificationId, "PENDING", "Added to waiting queue (block time)");
            log.info("{} ⏰ 금지 시간 - 기존 Notification 상태 업데이트. billId={}, notificationId={}", 
                    traceInfo, message.getBillId(), existingNotificationId);
        } else {
            // 신규 메시지 → 새 Notification 생성
            saveNotification(message, "PENDING", "Added to waiting queue (block time)");
            log.info("{} ⏰ 금지 시간 - 신규 Notification 생성. billId={}", traceInfo, message.getBillId());
        }
    }

    /**
     * 이메일 발송 처리
     */
    private void sendEmail(BillingMessageDto message, boolean isRetry, 
                           Long existingNotificationId, String traceInfo) {
        try {
            // 이메일 발송
            emailService.sendEmail(message);
            
            // 발송 성공 처리 (sent:msg 저장 + retry:msg 삭제)
            duplicateCheckHandler.onSendSuccess(message.getBillId());
            
            // Notification 저장/업데이트
            if (isRetry && existingNotificationId != null) {
                // 재시도 메시지 → 기존 Notification 상태 업데이트
                updateNotificationToSent(existingNotificationId);
                log.info("{} 📧 이메일 발송 성공 (재시도). billId={}, notificationId={}", 
                        traceInfo, message.getBillId(), existingNotificationId);
            } else {
                // 신규 메시지 → 새 Notification 생성
                saveNotification(message, "SENT", null);
                log.info("{} 📧 이메일 발송 성공 (신규). billId={}", traceInfo, message.getBillId());
            }

        } catch (Exception e) {
            log.error("{} ❌ 이메일 발송 실패. billId={}", traceInfo, message.getBillId());
            
            // Notification 저장/업데이트 (FAILED)
            if (isRetry && existingNotificationId != null) {
                // 재시도 메시지 → 기존 Notification 에러 업데이트
                updateNotificationToFailed(existingNotificationId, e.getMessage());
            } else {
                // 신규 메시지 → 새 Notification 생성 (FAILED, retry_count=0)
                saveNotification(message, "FAILED", e.getMessage());
            }
            
            throw new RuntimeException(e);
        }
    }

    /**
     * 신규 Notification 저장
     */
    private void saveNotification(BillingMessageDto message, String status, String errorMessage) {
        String content = String.format(
            "[LG U+ 청구 알림]\n청구 년월: %s\n총 청구 금액: %,d원\n납부 기한: %s",
            message.getBillYearMonth(),
            message.getTotalAmount() != null ? message.getTotalAmount() : 0,
            message.getDueDate() != null ? message.getDueDate() : "미정"
        );

        Notification notification = Notification.builder()
            .userId(message.getUserId())
            .notificationType("EMAIL")
            .notificationStatus(status)
            .billId(message.getBillId())
            .recipient(message.getRecipientEmail())
            .content(content)
            .retryCount(0)  // 신규는 항상 0
            .scheduledAt(LocalDateTime.now())
            .sentAt("SENT".equals(status) ? LocalDateTime.now() : null)
            .errorMessage(errorMessage)
            .createdAt(LocalDateTime.now())
            .build();

        notificationRepository.save(notification);
        log.debug("💾 신규 Notification 저장. status={}, billId={}", status, message.getBillId());
    }

    /**
     * 기존 Notification 상태만 업데이트
     */
    private void updateNotificationStatus(Long notificationId, String status, String errorMessage) {
        Optional<Notification> optNotification = notificationRepository.findById(notificationId);
        
        if (optNotification.isPresent()) {
            Notification existing = optNotification.get();
            Notification updated = Notification.builder()
                .notificationId(existing.getNotificationId())
                .userId(existing.getUserId())
                .notificationType(existing.getNotificationType())
                .notificationStatus(status)
                .billId(existing.getBillId())
                .recipient(existing.getRecipient())
                .content(existing.getContent())
                .retryCount(existing.getRetryCount())
                .scheduledAt(existing.getScheduledAt())
                .sentAt(existing.getSentAt())
                .errorMessage(errorMessage)
                .createdAt(existing.getCreatedAt())
                .build();
            
            notificationRepository.save(updated);
            log.debug("💾 Notification 상태 업데이트. notificationId={}, status={}", notificationId, status);
        } else {
            log.warn("⚠️ Notification을 찾을 수 없음. notificationId={}", notificationId);
        }
    }

    /**
     * 기존 Notification을 SENT로 업데이트
     */
    private void updateNotificationToSent(Long notificationId) {
        Optional<Notification> optNotification = notificationRepository.findById(notificationId);
        
        if (optNotification.isPresent()) {
            Notification existing = optNotification.get();
            Notification updated = Notification.builder()
                .notificationId(existing.getNotificationId())
                .userId(existing.getUserId())
                .notificationType(existing.getNotificationType())
                .notificationStatus("SENT")
                .billId(existing.getBillId())
                .recipient(existing.getRecipient())
                .content(existing.getContent())
                .retryCount(existing.getRetryCount())
                .scheduledAt(existing.getScheduledAt())
                .sentAt(LocalDateTime.now())  // 발송 시간 기록
                .errorMessage(null)  // 성공이므로 에러 메시지 제거
                .createdAt(existing.getCreatedAt())
                .build();
            
            notificationRepository.save(updated);
            log.debug("💾 Notification SENT 업데이트. notificationId={}", notificationId);
        }
    }

    /**
     * 기존 Notification을 FAILED로 업데이트
     */
    private void updateNotificationToFailed(Long notificationId, String errorMessage) {
        Optional<Notification> optNotification = notificationRepository.findById(notificationId);
        
        if (optNotification.isPresent()) {
            Notification existing = optNotification.get();
            Notification updated = Notification.builder()
                .notificationId(existing.getNotificationId())
                .userId(existing.getUserId())
                .notificationType(existing.getNotificationType())
                .notificationStatus("FAILED")
                .billId(existing.getBillId())
                .recipient(existing.getRecipient())
                .content(existing.getContent())
                .retryCount(existing.getRetryCount())  // 재시도 카운트는 RetryService에서 증가
                .scheduledAt(existing.getScheduledAt())
                .sentAt(null)
                .errorMessage(errorMessage)
                .createdAt(existing.getCreatedAt())
                .build();
            
            notificationRepository.save(updated);
            log.debug("💾 Notification FAILED 업데이트. notificationId={}", notificationId);
        }
    }
}