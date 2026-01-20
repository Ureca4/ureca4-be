package com.ureca.billing.batch.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxMessageRelay {

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;

    // application.yml의 토픽명 확인
    @Value("${app.kafka.topics.billing-notification}")
    private String topicName;

    // 0.1초마다 100개씩 '고속' 처리
    @Scheduled(fixedDelay = 100)
    public void dispatch() {
        // 1. READY 상태인 이벤트 100개 조회
        String selectSql = "SELECT event_id, payload FROM OUTBOX_EVENTS WHERE status = 'READY' LIMIT 100";
        List<OutboxEvent> events = jdbcTemplate.query(selectSql, (rs, rowNum) ->
                new OutboxEvent(rs.getString("event_id"), rs.getString("payload"))
        );

        if (events.isEmpty()) return;

        // 2. 카프카로 병렬 전송 (비동기)
        List<CompletableFuture<SendResult<String, String>>> futures = events.stream()
                .map(event -> kafkaTemplate.send(topicName, event.eventId(), event.payload()))
                .toList();

        // 3. 모든 전송이 끝날 때까지 대기 (Kafka가 빠르니 금방 끝남)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("Kafka 전송 중 일부 실패, 이번 배치는 건너뜀");
            return;
        }

        // 4. [핵심] DB 업데이트를 한 번의 쿼리로 처리 (Bulk Update)
        List<String> eventIds = events.stream().map(OutboxEvent::eventId).toList();

        // "UPDATE ... WHERE event_id IN (?, ?, ...)" 동적 쿼리 생성
        String inSql = String.join(",", Collections.nCopies(eventIds.size(), "?"));
        String updateSql = String.format(
                "UPDATE OUTBOX_EVENTS SET status = 'PUBLISHED', updated_at = NOW() WHERE event_id IN (%s)",
                inSql
        );

        // 쿼리 한 방으로 100개 상태 변경!
        jdbcTemplate.update(updateSql, eventIds.toArray());

        log.info("Flushed {} events instantly.", events.size());
    }

    private record OutboxEvent(String eventId, String payload) {}
}