package com.ureca.billing.notification.consumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DeadLetterConsumer {

    // billing-topic.DLT (죽은 편지함)만 감시하는 녀석
    @KafkaListener(topics = "billing-topic.DLT", groupId = "dlq-group")
    public void listenDeadLetter(String message) {
        // 여기서는 에러 없이 로그만 찍거나, DB에 '실패_목록'으로 저장합니다.
        log.error("🚑 [DLQ 수신] 실패했던 메시지 확인: {}", message);
        // 일단 메시지만 확인
    }
}
