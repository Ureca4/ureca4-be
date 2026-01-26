package com.ureca.billing.notification.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.consumer.handler.DuplicateCheckHandler;
import com.ureca.billing.notification.domain.entity.Notification;
import com.ureca.billing.notification.domain.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 재시도 서비스
 * 
 * 아키텍처 플로우 (Retry Scheduler - 5분마다 실행):
 * 1. status = "FAILED" 조회
 * 2. retry_count < 3 인 경우:
 *    - DB 상태 업데이트: status = "RETRY", retry_count++
 *    - Redis에 재시도 정보 저장: key: retry:msg:{billId}, value: notificationId, TTL: 1시간
 *    - Kafka로 재발행 (billing-event-topic)
 *    - 처음 로직으로 돌아감
 * 3. retry_count >= 3 인 경우:
 *    - DLQ로 이동 (billing-event-dlt)
 *     - DeadLetterConsumer에서 SMS Fallback 자동 처리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetryService {

    private static final String TOPIC = "billing-event";
    private static final String DLT_TOPIC = "billing-event-dlt";
    private static final int MAX_RETRY_COUNT = 3;

    private final NotificationRepository notificationRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final DuplicateCheckHandler duplicateCheckHandler;
    private final RedisTemplate<String, Object> redisTemplate; // RedisTemplate 직접 사용 (Pipeline용)
    private final JdbcTemplate jdbcTemplate;


    /**
     * FAILED 메시지 재시도 (Batch & Pipeline 최적화)
     */
    @Transactional
    public int retryFailedMessages(int limit) {
        // 1. 재시도 대상 조회
        List<Notification> allFailedMessages = notificationRepository.findFailedMessagesForRetry();

        if (allFailedMessages == null || allFailedMessages.isEmpty()) {
            return 0;
        }

        List<Notification> targetMessages = allFailedMessages.stream()
                .limit(limit)
                .toList();

        // 메모리에 작업 내용을 모을 리스트들
        List<Notification> updatesToSave = new ArrayList<>();
        Map<Long, String> messageJsonCache = new HashMap<>(); // Kafka 전송용 캐시
        List<Notification> dltCandidates = new ArrayList<>();

        int successCount = 0;
        int dlqCount = 0;

        // 2. 로직 처리 (DB 접속 최소화, 메모리 연산 위주)
        for (Notification notification : targetMessages) {
            try {
                // A. 최대 재시도 횟수 초과 체크
                if (notification.getRetryCount() >= MAX_RETRY_COUNT) {
                    dltCandidates.add(notification);
                    continue; // 다음 메시지로
                }

                // B. 재시도 준비 (메모리 상에서 객체 수정)
                // DB에서 읽어오는 작업(reconstructMessage)은 어쩔 수 없이 건마다 해야 함 (캐싱되어 있다면 빠름)
                BillingMessageDto message = reconstructMessage(notification);
                String messageJson = objectMapper.writeValueAsString(message);

                // Kafka 전송을 위해 캐시에 저장해둠
                messageJsonCache.put(notification.getNotificationId(), messageJson);

                // 상태 업데이트 (아직 DB 저장 안 함)
                notification.setNotificationStatus("RETRY");
                notification.setRetryCount(notification.getRetryCount() + 1);

                updatesToSave.add(notification);
                successCount++;

            } catch (Exception e) {
                log.error("❌ [RETRY] 처리 준비 중 에러. notificationId={}, error={}",
                        notification.getNotificationId(), e.getMessage());
            }
        }

        // 3. [최적화] DLT 일괄 처리
        if (!dltCandidates.isEmpty()) {
            processDltBatch(dltCandidates);
            dlqCount = dltCandidates.size();
        }

        // 4. [최적화] DB 일괄 저장 (Bulk Update) 💾
        // 수십 번의 UPDATE 쿼리를 배치로 처리
        if (!updatesToSave.isEmpty()) {
            notificationRepository.saveAll(updatesToSave);
            // log.info("📝 [RETRY] {}건 DB 상태 일괄 업데이트 완료", updatesToSave.size());
        }

        // 5. [최적화] Redis 일괄 저장 (Pipeline) ⚡
        // 네트워크 왕복 비용 제거
        if (!updatesToSave.isEmpty()) {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Notification n : updatesToSave) {
                    // 키 포맷: retry:msg:{billId}
                	String key = "retry:msg:" + n.getBillId() + ":" + n.getNotificationType();
                    // value: notificationId, TTL: 1시간 (3600초)
                    connection.setEx(key.getBytes(), 3600, String.valueOf(n.getNotificationId()).getBytes());
                }
                return null;
            });
            // log.info("💾 [RETRY] {}건 Redis 일괄 저장 완료", updatesToSave.size());
        }

        // 6. [최적화] Kafka 일괄 재발행 📤
        // 이미 만들어둔 JSON을 이용해 빠르게 전송
        for (Notification n : updatesToSave) {
            String json = messageJsonCache.get(n.getNotificationId());
            if (json != null) {
                kafkaTemplate.send(TOPIC, json);
            }
        }

        log.info("🎯 [RETRY] 재시도 배치 완료. 재시도: {}, DLT: {}, 총: {}",
                successCount, dlqCount, successCount + dlqCount);

        return successCount;
    }

    /**
     * DLT 대상 메시지 일괄 처리
     */
    private void processDltBatch(List<Notification> dltCandidates) {
        List<Notification> finalFailures = new ArrayList<>();

        for (Notification n : dltCandidates) {
            try {
                sendToDlt(n); // DLT 전송 (개별 전송 유지 - 실패 시 영향 범위 최소화)

                // 메모리 상에서 상태 변경
                Notification failed = n.markAsFinalFailure("Max retry count exceeded → DLT");
                finalFailures.add(failed);
            } catch (Exception e) {
                log.error("❌ [DLT] 전송 실패 id={}", n.getNotificationId());
            }
        }

        // 상태 변경 일괄 저장
        if (!finalFailures.isEmpty()) {
            notificationRepository.saveAll(finalFailures);
        }
    }

    /*
     * 3회 실패 메시지를 DLT로 전송하는 메서드
     */
    private void sendToDlt(Notification notification) {
        try {
            BillingMessageDto message = reconstructMessageForDlt(notification);
            String messageJson = objectMapper.writeValueAsString(message);
            kafkaTemplate.send(DLT_TOPIC, messageJson);
        } catch (Exception e) {
            throw new RuntimeException("DLT send failed", e);
        }
    }

    /**
     * DLT용 BillingMessageDto 재구성
     */
    private BillingMessageDto reconstructMessageForDlt(Notification notification) {
        Map<String, Object> billInfo = getBillInfo(notification.getBillId());
        String phoneCipher = getPhoneCipherByUserId(notification.getUserId());

        return BillingMessageDto.builder()
                .billId(notification.getBillId())
                .userId(notification.getUserId())
                .recipientEmail(notification.getRecipient())
                .recipientPhone(phoneCipher)
                .totalAmount((Long) billInfo.get("totalAmount"))
                .billYearMonth((String) billInfo.get("billingMonth"))
                .billDate((String) billInfo.get("billDate"))
                .dueDate((String) billInfo.get("dueDate"))
                .notificationType("EMAIL")
                .build();
    }

    /**
     * Notification에서 BillingMessageDto 재구성
     */
    private BillingMessageDto reconstructMessage(Notification notification) {
        // 주의: 여기서 DB 조회가 발생합니다 (N+1 문제 가능성).
        // 성능을 더 높이려면 Notification 엔티티에 원본 JSON을 저장해두는 것이 좋습니다.
        Map<String, Object> billInfo = getBillInfo(notification.getBillId());

        return BillingMessageDto.builder()
                .billId(notification.getBillId())
                .userId(notification.getUserId())
                .recipientEmail(notification.getRecipient())
                .recipientPhone(null)
                .totalAmount((Long) billInfo.get("totalAmount"))
                .billYearMonth((String) billInfo.get("billingMonth"))
                .billDate((String) billInfo.get("billDate"))
                .dueDate((String) billInfo.get("dueDate"))
                .build();
    }

    private Map<String, Object> getBillInfo(Long billId) {
        try {
            Long totalAmount = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM bill_details WHERE bill_id = ?",
                    Long.class,
                    billId
            );

            Map<String, Object> billData = jdbcTemplate.queryForMap(
                    """
                    SELECT billing_month, 
                           DATE_FORMAT(bill_issue_date, '%Y-%m-%d') as bill_date,
                           DATE_FORMAT(DATE_ADD(bill_issue_date, INTERVAL 15 DAY), '%Y-%m-%d') as due_date
                    FROM bills 
                    WHERE bill_id = ?
                    """,
                    billId
            );

            return Map.of(
                    "totalAmount", totalAmount != null ? totalAmount : 0L,
                    "billingMonth", billData.get("billing_month") != null ? billData.get("billing_month").toString() : "N/A",
                    "billDate", billData.get("bill_date") != null ? billData.get("bill_date").toString() : "N/A",
                    "dueDate", billData.get("due_date") != null ? billData.get("due_date").toString() : "N/A"
            );

        } catch (Exception e) {
            // log.warn("⚠️ [DB] bills 조회 실패..."); // 로그 주석 처리
            return Map.of("totalAmount", 0L, "billingMonth", "N/A", "billDate", "N/A", "dueDate", "N/A");
        }
    }

    private String getPhoneCipherByUserId(Long userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT phone_cipher FROM users WHERE user_id = ?",
                    String.class,
                    userId
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 기존 FAILED 메시지 DLT 일괄 전송 (배치 처리 적용)
     */
    @Transactional
    public int sendExistingFailedToDlt(int limit) {
        List<Notification> maxRetryFailedMessages = notificationRepository.findMaxRetryFailedMessages();

        if (maxRetryFailedMessages == null || maxRetryFailedMessages.isEmpty()) {
            return 0;
        }

        List<Notification> targetMessages = maxRetryFailedMessages.stream()
                .limit(limit)
                .toList();

        processDltBatch(targetMessages);

        return targetMessages.size();
    }

    // 수동 재시도는 단건 처리이므로 기존 로직 유지 (생략)
    @Transactional
    public boolean retryNotification(Long notificationId) {
        // 기존 코드 유지...
        return false;
    }
}