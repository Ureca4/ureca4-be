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
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;

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

    private final ForkJoinPool customThreadPool = new ForkJoinPool(50);

    @KafkaListener(
            topics = "billing-event",
            groupId = "notification-group",
            containerFactory = "kafkaListenerContainerFactory",
            concurrency = "20" // 파티션 개수에 맞춰 설정
    )
    public void consume(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        long startTime = System.currentTimeMillis();
        int batchSize = records.size();
        log.info("[Batch] {}개 메시지 수신 시작", batchSize);

        // 1. Thread-Safe하고 Lock이 없는 큐 사용 (병목 제거)
        Queue<Notification> notificationsToSave = new ConcurrentLinkedQueue<>();

        // 2. [핵심 2] 커스텀 스레드 풀로 병렬 처리 실행 ⚡
        try {
            customThreadPool.submit(() -> {
                // 이 안에서 parallelStream은 우리가 만든 50개 스레드를 사용함
                records.parallelStream().forEach(record -> {
                    try {
                        Notification notification = processSingleMessage(record);
                        if (notification != null) {
                            notificationsToSave.add(notification);
                        }
                    } catch (Exception e) {
                        log.error("메시지 처리 중 에러: {}", record.value(), e);
                    }
                });
            }).get(); // 모든 작업이 끝날 때까지 대기
        } catch (Exception e) {
            log.error("배치 병렬 처리 중 심각한 에러", e);
            throw new RuntimeException(e);
        }

        // 3. DB 일괄 저장 (Bulk Insert/Update)
        // 수백 번의 INSERT 쿼리를 한 번의 트랜잭션으로 처리
        if (!notificationsToSave.isEmpty()) {
            notificationRepository.saveAll(notificationsToSave);
            log.info("[Batch] {}개 알림 상태 DB 저장 완료", notificationsToSave.size());
        }

        // 4. 일괄 커밋 (Batch Commit)
        ack.acknowledge();

        long duration = System.currentTimeMillis() - startTime;
        log.info("[Batch] {}개 처리 완료 (소요시간: {}ms)", batchSize, duration);
    }



    private Notification processSingleMessage(ConsumerRecord<String, String> record){
        String traceInfo = String.format("[P%d-0%d]", record.partition(), record.offset());

        try{
            // Json 파싱
            BillingMessageDto message = objectMapper.readValue(record.value(), BillingMessageDto.class);
            String notificationType = message.getNotificationType() != null ? message.getNotificationType() : "EMAIL";

            // 메시지 상태 체크
            CheckResult checkResult = duplicateCheckHandler.checkMessageStatus(message.getBillId(), notificationType);

            // 중복이면 null 반환 (저장 안 함)
            if (checkResult.isDuplicate()) {
                return null;
            }

            boolean isRetry = checkResult.isRetry();
            Long existingNotificationId = checkResult.getNotificationId();

            // 금지 시간 체크 (22:00 ~ 08:00)
            if (policyService.isBlockTime()) {
                // Redis 대기열에 저장
                queueService.addToQueue(record.value());

                // PENDING 상태의 Notification 객체 생성/반환
                return createOrUpdateNotificationEntity(
                        message, notificationType, "PENDING",
                        "Added to waiting queue (block time)",
                        isRetry, existingNotificationId
                );
            }
            try {
                NotificationHandler handler = handlerFactory.getHandler(notificationType);
                handler.handle(message, traceInfo); // 실제 발송 (I/O)

                duplicateCheckHandler.onSendSuccess(message.getBillId(), notificationType);

                // SENT 상태의 Notification 객체 생성/반환
                return createOrUpdateNotificationEntity(
                        message, notificationType, "SENT",
                        null,
                        isRetry, existingNotificationId
                );

            } catch (Exception e) {
                log.error("{} 발송 실패: {}", traceInfo, e.getMessage());

                // FAILED 상태의 Notification 객체 생성/반환
                return createOrUpdateNotificationEntity(
                        message, notificationType, "FAILED",
                        e.getMessage(),
                        isRetry, existingNotificationId
                );
            }
        } catch (Exception e){
            log.error("{} JSON 파싱 또는 로직 에러: {}", traceInfo, e.getMessage());
            return null;
        }
    }

    private Notification createOrUpdateNotificationEntity(
            BillingMessageDto message,
            String notificationType,
            String status,
            String errorMessage,
            boolean isRetry,
            Long existingNotificationId
    ) {
        String content = createNotificationContent(message, notificationType);
        String recipient = getRecipient(message, notificationType);

        Notification.NotificationBuilder builder = Notification.builder()
                .userId(message.getUserId())
                .notificationType(notificationType)
                .notificationStatus(status)
                .billId(message.getBillId())
                .recipient(recipient)
                .content(content)
                .errorMessage(errorMessage)
                .scheduledAt(LocalDateTime.now());

        if (isRetry && existingNotificationId != null) {
            // 재시도: 기존 ID 사용 (Update)
            // 주의: DB에서 기존 데이터를 조회해서 createdAt 등을 유지하려면
            // 여기서 findById를 할 수도 있지만, 성능을 위해 주요 필드만 업데이트 덮어쓰기하거나
            // JPA의 동작 방식(ID가 있으면 Merge)을 이용합니다.
            builder.notificationId(existingNotificationId);

            // 기존 재시도 횟수를 알 수 없다면 별도 로직이 필요하지만,
            // 여기서는 단순화를 위해 DB 조회를 최소화하거나 retry_count는 그대로 둡니다.
            // (정확한 구현을 위해선 findById가 필요할 수 있음. 여기서는 성능 우선으로 ID만 세팅)
        } else {
            // 신규: ID 없음 (Insert), 카운트 0
            builder.retryCount(0);
            builder.createdAt(LocalDateTime.now());
        }

        if ("SENT".equals(status)) {
            builder.sentAt(LocalDateTime.now());
        }

        return builder.build();
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
}