package com.ureca.billing.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
@Slf4j
@Configuration
public class KafkaConsumerConfig {
    // KafkaListener 컨테이너 팩토리 커스텀 설정
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(ConsumerFactory<String, Object> consumerFactory, KafkaTemplate<String, Object> kafkaTemplate){

        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // 1. 수동 커밋 모드 설정
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

        // ==========================================
        // 핵심: 재시도 및 DLQ 전략 설정
        // ==========================================

        // 1. Recoverer: 3번 실패하면 'billing-topic.DLT' 토픽으로 자동 전송
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (r, e) -> {
                    log.error("[DLQ 이동] 3회 재시도 실패. Topic: {}, Value: {}", r.topic(), r.value());
                    // 기본적으로 원래토픽명.DLT 로 보냅니다. (billing-topic.DLT)
                    return new org.apache.kafka.common.TopicPartition(r.topic() + ".DLT", r.partition());
                });

        // 2. BackOff: 1초 간격으로 3번 재시도
        FixedBackOff backOff = new FixedBackOff(1000L, 3);

        // 3. 에러 핸들러 장착
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }

}
