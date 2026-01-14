package com.ureca.billing.notification.consumer;

import com.ureca.billing.notification.consumer.handler.MessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailConsumer {
    
    private final MessageHandler messageHandler;
    
    @KafkaListener(
        topics = "billing-event",
        groupId = "notification-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(String message, Acknowledgment ack) {
        try {
            log.info("📥 Kafka message received: {}", message.substring(0, Math.min(100, message.length())));
            
            // 메시지 처리
            messageHandler.handleMessage(message);
            
            // 수동 커밋
            ack.acknowledge();
            log.info("✅ Message processed and committed");
            
        } catch (Exception e) {
            log.error("❌ Failed to process message: {}", e.getMessage(), e);
            // 커밋하지 않음 → 재처리됨
        }
    }
}