package com.ureca.billing.notification.consumer;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.core.security.crypto.AesUtil;
import com.ureca.billing.core.security.crypto.CryptoKeyProvider;
import com.ureca.billing.notification.consumer.handler.DuplicateCheckHandler;
import com.ureca.billing.notification.consumer.handler.DuplicateCheckHandler.CheckResult;
import com.ureca.billing.notification.domain.entity.Notification;
import com.ureca.billing.notification.domain.repository.NotificationRepository;
import com.ureca.billing.notification.handler.NotificationHandler;
import com.ureca.billing.notification.handler.NotificationHandlerFactory;
import com.ureca.billing.notification.service.RedisUserPrefCache;
import com.ureca.billing.notification.service.RedisUserPrefCache.QuietTimeResult;
import com.ureca.billing.notification.service.ScheduledQueueService;
import com.ureca.billing.notification.service.WaitingQueueService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Kafka 메시지 Consumer (멀티 채널 지원 + Redis 캐싱)
 *  Redis 캐싱 적용:
 * 1. 사용자별 금지시간 체크 (Redis 캐시)
 * 2. 사용자별 예약발송시간 체크 (Redis 캐시)
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class BillingEventConsumer {

    private final ObjectMapper objectMapper;
    private final DuplicateCheckHandler duplicateCheckHandler;
    private final RedisUserPrefCache userPrefCache;
    private final WaitingQueueService waitingQueueService;
    private final ScheduledQueueService scheduledQueueService;
    private final NotificationHandlerFactory handlerFactory;
    private final NotificationRepository notificationRepository;
    private final CryptoKeyProvider keyProvider;

    private final ForkJoinPool customThreadPool = new ForkJoinPool(50);

    @KafkaListener(
            topics = "billing-event",
            groupId = "notification-group",
            containerFactory = "kafkaListenerContainerFactory",
            concurrency = "50" // 파티션 개수에 맞춰 설정
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
            // 1. 암호화된 payload 복호화
            String encryptedPayload = record.value();
            String decryptedPayload;
            try {
                decryptedPayload = AesUtil.decrypt(encryptedPayload, keyProvider.getCurrentKey());
            } catch (Exception e) {
                log.error("{} 🔓 복호화 실패: {}", traceInfo, e.getMessage());
                // 복호화 실패 시 원본을 그대로 시도 (하위 호환성)
                decryptedPayload = encryptedPayload;
            }
            
            // 2. 복호화된 JSON 파싱
            BillingMessageDto message = objectMapper.readValue(decryptedPayload, BillingMessageDto.class);
            String channel = message.getNotificationType() != null ? message.getNotificationType().toUpperCase() : "EMAIL";

            log.debug("{} 메시지 처리 시작: billId={}, userId={}, channel={}", 
                    traceInfo, message.getBillId(), message.getUserId(), channel);
            
            // 메시지 상태 체크
            CheckResult checkResult = duplicateCheckHandler.checkMessageStatus(message.getBillId(), channel);

            // 중복이면 null 반환 (저장 안 함)
            if (checkResult.isDuplicate()) {
            	log.debug("{} 🔄 중복 메시지 스킵: billId={}", traceInfo, message.getBillId());
                return null;
            }

            boolean isRetry = checkResult.isRetry();
            Long existingNotificationId = checkResult.getNotificationId();

            // - 첫 시도: 1 (1% 실패율)
            // - 재시도: 2 이상 (30% 실패율)
            int deliveryAttempt = isRetry ? 2 : 1;
            
            
            YearMonth billingMonth = parseBillingMonth(message.getBillYearMonth());
            Optional<LocalDateTime> scheduledTimeOpt = userPrefCache.getScheduledTime(
                message.getUserId(), 
                channel, 
                billingMonth
            );

            if (scheduledTimeOpt.isPresent()) {
                LocalDateTime scheduledAt = scheduledTimeOpt.get();

                // 예약 시간이 이미 지났으면 즉시 발송
                if (scheduledAt.isAfter(LocalDateTime.now())) {
                    log.info("{} 📅 예약발송: userId={}, billId={}, scheduledAt={}", 
                        traceInfo, message.getUserId(), message.getBillId(), scheduledAt);
                 // 처리 중 마킹 (중복 방지)
                    duplicateCheckHandler.markAsProcessing(message.getBillId(), channel);

                    // ScheduledQueue에 저장
                    scheduledQueueService.schedule(message, scheduledAt, channel);

                    return createNotificationEntity(
                        message, channel, "SCHEDULED",
                        "예약 발송: " + scheduledAt,
                        isRetry, existingNotificationId,
                        scheduledAt
                    );
                } else {
                    log.debug("{} ⏰ 예약시간 지남 → 즉시발송: scheduledAt={}", traceInfo, scheduledAt);
                }
            }
            
            LocalTime now = LocalTime.now();
            QuietTimeResult quietResult = userPrefCache.checkQuietTime(
                message.getUserId(), 
                channel, 
                now
            );

            if (quietResult.isQuiet) {
                log.info("{} 🔕 금지시간: userId={}, reason={}, source={}", 
                    traceInfo, message.getUserId(), quietResult.reason, quietResult.source);
                // 처리 중 마킹 (중복 방지)
                duplicateCheckHandler.markAsProcessing(message.getBillId(), channel);
                // 대기열에는 복호화된 JSON 저장 (재발송 시 다시 암호화할 필요 없음)
                waitingQueueService.addToQueue(decryptedPayload);

                // PENDING 상태의 Notification 객체 생성/반환
                return createOrUpdateNotificationEntity(
                        message, channel, "PENDING",
                        quietResult.getMessage(),
                        isRetry, existingNotificationId
                );
            }
            
            
            try {
                NotificationHandler handler = handlerFactory.getHandler(channel);
                handler.handle(message, traceInfo, deliveryAttempt);

                duplicateCheckHandler.onSendSuccess(message.getBillId(), channel);
                
                //log.info("{} ✅ 발송 성공: billId={}, userId={}, channel={}", 
                       // traceInfo, message.getBillId(), message.getUserId(), channel);

                // SENT 상태의 Notification 객체 생성/반환
                return createOrUpdateNotificationEntity(
                        message, channel, "SENT",
                        null,
                        isRetry, existingNotificationId
                );

            } catch (Exception e) {
                log.error("{} 발송 실패:billId={}, error={}", traceInfo,  message.getBillId(), e.getMessage());

                // FAILED 상태의 Notification 객체 생성/반환
                return createOrUpdateNotificationEntity(
                        message, channel, "FAILED",
                        e.getMessage(),
                        isRetry, existingNotificationId
                );
            }
        } catch (Exception e) {
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
    	   return createNotificationEntity(
    	            message, notificationType, status, errorMessage, 
    	            isRetry, existingNotificationId, LocalDateTime.now()
    	        );
    	    }

    	    /**
    	     * Notification 엔티티 생성 
    	     */
    	    private Notification createNotificationEntity(
    	            BillingMessageDto message,
    	            String notificationType,
    	            String status,
    	            String errorMessage,
    	            boolean isRetry,
    	            Long existingNotificationId,
    	            LocalDateTime scheduledAt
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
                .scheduledAt(scheduledAt);

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
     * 청구 월 파싱 (예: "202501" → 2025-01)
     */
    private YearMonth parseBillingMonth(String billYearMonth) {
        if (billYearMonth == null || billYearMonth.length() < 6) {
            return YearMonth.now();
        }
        try {
            int year = Integer.parseInt(billYearMonth.substring(0, 4));
            int month = Integer.parseInt(billYearMonth.substring(4, 6));
            return YearMonth.of(year, month);
        } catch (Exception e) {
            return YearMonth.now();
        }

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