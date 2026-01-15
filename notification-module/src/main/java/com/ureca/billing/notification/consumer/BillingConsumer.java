package com.ureca.billing.notification.consumer;

import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.domain.dto.BillingMessage;
import com.ureca.billing.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingConsumer {

    private final NotificationService notificationService;

    /**
     * Kafka 리스너
     * ack: 수동 커밋을 위해 받음 (선택 사항이지만 대용량 처리 시 권장)
     */
    @KafkaListener(topics = "billing-topic", groupId = "billing-group", containerFactory = "kafkaListenerContainerFactory")
    public void listen(ConsumerRecord<String, BillingMessageDto> record, Acknowledgment ack) {
        BillingMessageDto message = record.value();

        // 로깅용 추적 ID (Offset 정보 포함)
        String traceInfo = String.format("[Topic:%s-Part:%d-Off:%d]",
                record.topic(), record.partition(), record.offset());

        log.info("{} 📨 메시지 수신 - BillID: {}", traceInfo, message.getBillId());

        try {
            // 비즈니스 로직 실행
            notificationService.sendNotification(message);

            // 성공 시 Kafka에 "나 이거 처리했어" 보고 (Commit)
            ack.acknowledge();

        } catch (Exception e) {
            // 1% 확률로 실패하면 여기가 실행됨
            log.error("{} ❌ 처리 중 에러 발생: {}", traceInfo, e.getMessage());

            // 실무 팁: 여기서 재시도 로직을 넣거나, DLQ로 보내거나 결정함.
            // 지금은 단순히 에러 로그만 찍고 넘어가서 다음 메시지를 처리하게 함 (Non-blocking)
            // 필요 시 ack.acknowledge()를 안 부르면 Kafka가 다시 줄 수도 있음 (설정에 따라 다름)
        }
    }
}
