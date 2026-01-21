package com.ureca.billing.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic billingEventTopic() {
        return TopicBuilder.name("billing-event")  // 토픽 이름
                .partitions(3)
                .replicas(1)
                .build();
    }
}
