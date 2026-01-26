package com.ureca.billing.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class RestClientConfig {

    @Value("${app.notification.base-url:http://localhost:8082}")
    private String notificationBaseUrl;

    @Value("${app.batch.base-url:http://localhost:8081}")
    private String batchBaseUrl;

    @Bean
    public WebClient notificationWebClient() {
        return WebClient.builder()
                .baseUrl(notificationBaseUrl)
                .build();
    }

    @Bean
    public WebClient batchWebClient() {
        return WebClient.builder()
                .baseUrl(batchBaseUrl)
                .build();
    }
}
