package com.ureca.billing.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Consumer 설정
 * - 3개의 컨슈머 인스턴스 병렬 처리
 * - 재시도 및 DLT 전략
 * - 성능 최적화
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {
    
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;
    
    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
        
        // 기본 설정
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-group");
        
        // 성능 최적화
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);  // 한 번에 100개 처리
        config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024);  // 최소 1KB 데이터 대기
        config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);  // 최대 0.5초 대기
        
        // Offset 관리
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);  // 수동 커밋
        
        // Deserializer 설정
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, org.apache.kafka.common.serialization.StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, org.apache.kafka.common.serialization.StringDeserializer.class);
        
        // JSON 설정
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "com.ureca.billing.notification.domain.dto.BillingMessage");
        
        // Session timeout 설정
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);  // 30초
        config.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);  // 10초
        
        return new DefaultKafkaConsumerFactory<>(config);
    }
    
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate) {
        
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = 
            new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(consumerFactory);
        
        // 수동 커밋 모드
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        
        // 동시성 설정 (기본값, @KafkaListener의 concurrency로 오버라이드 가능)
        factory.setConcurrency(3);
        
        // Thread Pool 설정
        factory.getContainerProperties().setIdleBetweenPolls(100);  // Poll 간격 100ms
        
        // 재시도 횟수를 Consumer에서 확인할 수 있도록 헤더 활성화
        factory.getContainerProperties().setDeliveryAttemptHeader(true);
        
        // ==========================================
        // DLT (Dead Letter Topic) 전략
        // ==========================================
        
        // 1. Recoverer: 3회 재시도 실패 시 DLT로 전송
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) -> {
                log.error("🚨 [DLT 이동] 3회 재시도 실패 - Topic: {}, Partition: {}, Offset: {}", 
                    record.topic(), record.partition(), record.offset());
                log.error("🚨 [DLT 이동] Error: {}", exception.getMessage());
                
                // billing-event.DLT로 전송
                return new org.apache.kafka.common.TopicPartition(
                    record.topic() + ".DLT", 
                    record.partition()
                );
            }
        );
        
        // 2. BackOff: 1초 간격으로 3회 재시도
        FixedBackOff backOff = new FixedBackOff(1000L, 3);
        
        // 3. Error Handler 설정
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        
        // 재시도하지 않을 예외 지정 (선택사항)
        // errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        
        // 로깅 추가
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) -> {
            log.warn("⚠️ [재시도 {}회] Topic: {}, Partition: {}, Offset: {}, Error: {}", 
                deliveryAttempt, 
                record.topic(), 
                record.partition(), 
                record.offset(), 
                ex.getMessage());
        });
        
        factory.setCommonErrorHandler(errorHandler);
        
        return factory;
    }
}