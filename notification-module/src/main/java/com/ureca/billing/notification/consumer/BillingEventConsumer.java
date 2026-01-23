package com.ureca.billing.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;


import com.ureca.billing.notification.service.ScheduledQueueService;
import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.consumer.handler.DuplicateCheckHandler;
import com.ureca.billing.notification.consumer.handler.DuplicateCheckHandler.CheckResult;
import com.ureca.billing.notification.domain.entity.Notification;
import com.ureca.billing.notification.domain.repository.NotificationRepository;
import com.ureca.billing.notification.handler.NotificationHandler;
import com.ureca.billing.notification.handler.NotificationHandlerFactory;
import com.ureca.billing.notification.service.EmailService;
import com.ureca.billing.notification.service.MessagePolicyService;
import com.ureca.billing.notification.service.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Kafka 메시지 Consumer (멀티 채널 지원 + 예약 발송)
 * 
 * 아키텍처 플로우:
 * 1. Kafka 메시지 수신 (billing-event-topic)
 * 2. notificationType 확인 (EMAIL, SMS, PUSH)
 * 3. 중복 발송? → Redis 조회 키: sent:msg:{billId}:{type}
 *    - yes → skip
 *    - no → 재시도 메시지인지 확인
 * 4. 재시도 메시지? → Redis key: retry:msg:{billId} 조회
 *    - 재시도일 경우, 기존 Notification 이용
 *    - 새로운 메시지일 경우, 발송 때 Notification 생성
 * 5. 시스템 금지 시간? → WaitingQueue 저장 (다음날 08:00)
 * 6. 사용자 예약 발송 시간? → ScheduledQueue 저장 (사용자 선호 시간)
 * 7. NotificationHandlerFactory로 적절한 핸들러 선택
 *    - EMAIL → EmailNotificationHandler
 *    - SMS → SmsNotificationHandler
 *    - PUSH → PushNotificationHandler
 * 8. 핸들러 실행
 *    - 성공 → status = "SENT", sent:msg:{billId}:{type} 저장
 *    - 실패 → status = "FAILED", retry_count 증가
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingEventConsumer {

    private final ObjectMapper objectMapper;
    private final DuplicateCheckHandler duplicateCheckHandler;
    private final MessagePolicyService policyService;
    private final WaitingQueueService queueService;
    private final ScheduledQueueService scheduledQueueService;
    private final NotificationHandlerFactory handlerFactory;
    private final EmailService emailService;
    private final NotificationRepository notificationRepository;

    @KafkaListener(
        topics = "billing-event",
        groupId = "notification-group",
        containerFactory = "kafkaListenerContainerFactory",
        concurrency = "3"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack,
    		@Header(value = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {  // ✅ 재시도 횟수 헤더
        
        // deliveryAttempt가 null이면 1로 설정 (첫 시도)
        int attempt = (deliveryAttempt != null) ? deliveryAttempt : 1;
    	
        String traceInfo = String.format("[P%d-O%d-A%d]", record.partition(), record.offset(), attempt);
        long startTime = System.currentTimeMillis();

        log.info("{} 🔥 메시지 수신 (시도 {}회)", traceInfo, attempt);

        try {
            // 1. JSON 파싱
            String messageJson = record.value();
            BillingMessageDto message = objectMapper.readValue(messageJson, BillingMessageDto.class);

            String notificationType = message.getNotificationType() != null 
                ? message.getNotificationType() : "EMAIL"; // 기본값

            log.info("{} 📨 billId={}, userId={}, type={}", 
                traceInfo, message.getBillId(), message.getUserId(), notificationType);

            // 2. 메시지 상태 체크 (중복 + 재시도 통합)
            CheckResult checkResult = duplicateCheckHandler.checkMessageStatus(
                message.getBillId(), notificationType);
            
            // 2-1. 중복 메시지 → skip
            if (checkResult.isDuplicate()) {
                log.warn("{} ⚠️ 중복 메시지 스킵. billId={}, type={}", 
                    traceInfo, message.getBillId(), notificationType);
                ack.acknowledge();
                return;
            }
            
            // 2-2. 재시도 메시지 여부 확인
            boolean isRetry = checkResult.isRetry();
            Long existingNotificationId = checkResult.getNotificationId();
            
            if (isRetry) {
                log.info("{} 🔄 재시도 메시지. billId={}, type={}, notificationId={}", 
                        traceInfo, message.getBillId(), notificationType, existingNotificationId);
            } else {
                log.info("{} 📨 신규 메시지. billId={}, type={}", 
                    traceInfo, message.getBillId(), notificationType);
            }

            // 3. 시스템 금지 시간 체크 (22:00 ~ 08:00)
            if (policyService.isBlockTime()) {
                handleBlockTime(message, messageJson, notificationType, isRetry, existingNotificationId, traceInfo);
                ack.acknowledge();
                return;
            }
            
         // 4. 사용자 예약 발송 시간 체크
            LocalDateTime scheduledAt = scheduledQueueService.scheduleIfPreferred(message, notificationType);
            if (scheduledAt != null) {
                // 예약 발송 → ScheduledQueue에 저장됨
                handleScheduledSend(message, notificationType, scheduledAt, isRetry, existingNotificationId, traceInfo);
                ack.acknowledge();
                return;
            }

            // 5. 알림 발송 (타입별 핸들러 자동 선택)
            sendNotification(message, notificationType, isRetry, existingNotificationId, traceInfo, attempt);

            // 6. 수동 커밋
            ack.acknowledge();

            long duration = System.currentTimeMillis() - startTime;
            log.info("{} ✅ 처리 완료 ({}ms)", traceInfo, duration);

        } catch (Exception e) {
            log.error("{} ❌ 처리 실패(시도 {}회): {}", traceInfo, attempt, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 시스템 금지 시간대 처리
     * - 대기열에 메시지 저장
     * - Notification 상태를 PENDING으로 저장
     */
    private void handleBlockTime(BillingMessageDto message, String messageJson, String notificationType,
                                  boolean isRetry, Long existingNotificationId, String traceInfo) {
        // 대기열에 저장
        queueService.addToQueue(messageJson);
        
     // 🔥 중복 INSERT 방지: DB에 이미 레코드가 있는지 확인
        Optional<Notification> existingByBillId = notificationRepository.findByBillIdAndType(
            message.getBillId(), notificationType
        );
        
        if (existingByBillId.isPresent()) {
            // 이미 레코드 있음 → UPDATE만 수행
            Long dbNotificationId = existingByBillId.get().getNotificationId();
            updateNotificationStatus(dbNotificationId, "PENDING", "시스템 금지 시간대 (22:00~08:00)");
            log.info("{} 🏢 시스템 금지시간 - 기존 Notification 업데이트 (중복 방지). billId={}, type={}, notificationId={}", 
                    traceInfo, message.getBillId(), notificationType, dbNotificationId);
            
        } else {
            // 레코드 없음 → INSERT 수행
        	 saveNotification(message, notificationType, "PENDING", "시스템 금지 시간대 (22:00~08:00)");
             log.info("{} 🏢 시스템 금지시간 - 신규 Notification 생성. billId={}, type={}", 
                traceInfo, message.getBillId(), notificationType);
        }
    }
    
    /**
     *사용자 예약 발송 처리
     * - ScheduledQueue에 이미 저장됨 (scheduleIfPreferred에서)
     * - Notification 상태만 SCHEDULED로 저장
     */
    private void handleScheduledSend(BillingMessageDto message, String notificationType,
                                      LocalDateTime scheduledAt, boolean isRetry, 
                                      Long existingNotificationId, String traceInfo) {
        String scheduleMsg = String.format("사용자 예약 발송 (%s)", scheduledAt);
        
        if (isRetry && existingNotificationId != null) {
            updateNotificationStatus(existingNotificationId, "SCHEDULED", scheduleMsg);
            log.info("{} 📅 예약 발송 - 기존 Notification 상태 업데이트. billId={}, scheduledAt={}", 
                    traceInfo, message.getBillId(), scheduledAt);
        } else {
            saveNotificationWithSchedule(message, notificationType, "SCHEDULED", scheduleMsg, scheduledAt);
            log.info("{} 📅 예약 발송 - 신규 Notification 생성. billId={}, userId={}, scheduledAt={}", 
                traceInfo, message.getBillId(), message.getUserId(), scheduledAt);
        }
    }
    

    /**
     * 알림 발송 처리 (Factory 패턴)
     * @param attempt Kafka 재시도 횟수 (1=첫시도, 2이상=재시도)
     */
    private void sendNotification(BillingMessageDto message, String notificationType,
                                   boolean isRetry, Long existingNotificationId, String traceInfo, int attempt) {
        try {
            // 1. 타입에 맞는 핸들러 선택
            NotificationHandler handler = handlerFactory.getHandler(notificationType);
            
            log.info("{} 🎯 핸들러 선택됨: {} → {}", 
                traceInfo, notificationType, handler.getClass().getSimpleName());
            
            // 2. 핸들러 실행
            handler.handle(message, traceInfo, attempt);
            
            // 3. 발송 성공 처리 (sent:msg 저장 + retry:msg 삭제)
            duplicateCheckHandler.onSendSuccess(message.getBillId(), notificationType);
            
         // 🔥 중복 INSERT 방지: DB에 이미 레코드가 있는지 확인
            Optional<Notification> existingByBillId = notificationRepository.findByBillIdAndType(
                message.getBillId(), notificationType
            );
            
            if (existingByBillId.isPresent()) {
                // 이미 레코드 있음 → UPDATE만 수행
                Long dbNotificationId = existingByBillId.get().getNotificationId();
                updateNotificationToSent(dbNotificationId);
                log.info("{} ✅ 발송 성공 (재시도, 시도 {}회). billId={}, type={}, notificationId={}", 
                        traceInfo, attempt, message.getBillId(), notificationType, dbNotificationId);
            } else {
                // 레코드 없음 → INSERT 수행
                saveNotification(message, notificationType, "SENT", null);
                log.info("{} ✅ 발송 성공 (신규, 시도 {}회). billId={}, type={}", 
                    traceInfo, attempt, message.getBillId(), notificationType);
            }

        } catch (Exception e) {
        	log.error("{} ❌ 발송 실패 (시도 {}회). billId={}, type={}", 
                traceInfo, attempt, message.getBillId(), notificationType);
            
        	// 🔥 중복 INSERT 방지: DB에 이미 레코드가 있는지 확인
            Optional<Notification> existingByBillId = notificationRepository.findByBillIdAndType(
                message.getBillId(), notificationType
            );
            
            if (existingByBillId.isPresent()) {
                // 이미 레코드 있음 → UPDATE만 수행
                Long dbNotificationId = existingByBillId.get().getNotificationId();
                updateNotificationToFailed(dbNotificationId, e.getMessage());
            } else {
                // 레코드 없음 → INSERT 수행 (FAILED, retry_count=0)
                saveNotification(message, notificationType, "FAILED", e.getMessage());
            }
            
            throw new RuntimeException(e);
        }
    }

    /**
     * 신규 Notification 저장
     */
    private void saveNotification(BillingMessageDto message, String notificationType, 
                                   String status, String errorMessage) {
        String content = createNotificationContent(message, notificationType);
        String recipient = getRecipient(message, notificationType);

        Notification notification = Notification.builder()
            .userId(message.getUserId())
            .notificationType(notificationType)
            .notificationStatus(status)
            .billId(message.getBillId())
            .recipient(recipient)
            .content(content)
            .retryCount(0)  // 신규는 항상 0
            .scheduledAt(LocalDateTime.now())
            .sentAt("SENT".equals(status) ? LocalDateTime.now() : null)
            .errorMessage(errorMessage)
            .createdAt(LocalDateTime.now())
            .build();

        notificationRepository.save(notification);
        log.debug("💾 신규 Notification 저장. status={}, billId={}, type={}", 
            status, message.getBillId(), notificationType);
    }

    /**
     * 예약 시간 포함 Notification 저장
     */
    private void saveNotificationWithSchedule(BillingMessageDto message, String notificationType, 
                                               String status, String errorMessage, LocalDateTime scheduledAt) {
        String content = createNotificationContent(message, notificationType);
        String recipient = getRecipient(message, notificationType);

        Notification notification = Notification.builder()
            .userId(message.getUserId())
            .notificationType(notificationType)
            .notificationStatus(status)
            .billId(message.getBillId())
            .recipient(recipient)
            .content(content)
            .retryCount(0)
            .scheduledAt(scheduledAt)  // 🆕 예약 시간 저장
            .sentAt(null)
            .errorMessage(errorMessage)
            .createdAt(LocalDateTime.now())
            .build();

        notificationRepository.save(notification);
        log.debug("💾 예약 Notification 저장. status={}, billId={}, scheduledAt={}", 
            status, message.getBillId(), scheduledAt);
    }
    
    /**
     * 알림 타입별 수신자 정보 반환
     */
    private String getRecipient(BillingMessageDto message, String notificationType) {
        switch (notificationType.toUpperCase()) {
            case "EMAIL":
                return message.getRecipientEmail();
            case "SMS":
                return message.getRecipientPhone();
            case "PUSH":
                return "userId:" + message.getUserId();
            default:
                return message.getRecipientEmail();
        }
    }

    /**
     * 알림 타입별 컨텐츠 생성
     */
    private String createNotificationContent(BillingMessageDto message, String notificationType) {
        String baseContent = String.format(
            "[LG U+] %s 청구액 %,d원",
            message.getBillYearMonth(),
            message.getTotalAmount() != null ? message.getTotalAmount() : 0
        );

        switch (notificationType.toUpperCase()) {
            case "EMAIL":
                return String.format(
                    "[LG U+ 청구 알림]\n청구 년월: %s\n총 청구 금액: %,d원\n납부 기한: %s",
                    message.getBillYearMonth(),
                    message.getTotalAmount() != null ? message.getTotalAmount() : 0,
                    message.getDueDate() != null ? message.getDueDate() : "미정"
                );
            case "SMS":
                return baseContent + ". 납부기한: " + 
                    (message.getDueDate() != null ? message.getDueDate() : "미정");
            case "PUSH":
                return baseContent + ". 자세한 내용은 앱에서 확인하세요.";
            default:
                return baseContent;
        }
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