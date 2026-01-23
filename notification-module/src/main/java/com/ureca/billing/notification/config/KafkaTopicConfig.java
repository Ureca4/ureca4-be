package com.ureca.billing.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(
        name = "app.kafka.topic-creation.enabled", // 1. 감시할 yml 속성 이름
        havingValue = "true",                        // 2. 이 값일 때만 활성화 (문자열)
        matchIfMissing = true                        // 3. yml에 설정이 없으면 기본적으로 켜둠 (선택사항)
)
public class KafkaTopicConfig {

    @Bean
    public NewTopic billingEventTopic() {
        return TopicBuilder.name("billing-event")  // 토픽 이름
                .partitions(3)
                .replicas(1)
                .build();
    }
}
