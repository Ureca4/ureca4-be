package com.ureca.billing.notification.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
public class HealthController {

    @Value("${server.port:8082}")
    private String serverPort;

    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("module", "notification-module");
        response.put("port", serverPort);
        response.put("message", "시스템이 정상 작동 중입니다 ✅");
        
        log.info("Health check 요청");
        return response;
    }

    @GetMapping("/redis")
    public Map<String, Object> checkRedis() {
        Map<String, Object> response = new HashMap<>();
        try {
            String testKey = "health:notification:test";
            String testValue = "Redis 연결 성공!";
            
            redisTemplate.opsForValue().set(testKey, testValue);
            String result = redisTemplate.opsForValue().get(testKey);
            
            response.put("status", "UP ✅");
            response.put("redis", "Connected");
            response.put("message", result);
            
            log.info("✅ Redis 연결 테스트 성공");
            
        } catch (Exception e) {
            response.put("status", "DOWN ❌");
            response.put("redis", "Disconnected");
            response.put("error", e.getMessage());
            log.error("❌ Redis 연결 테스트 실패", e);
        }
        return response;
    }

    @GetMapping("/kafka")
    public Map<String, Object> checkKafka() {
        Map<String, Object> response = new HashMap<>();
        try {
            // Kafka 연결 확인 (간단한 메타데이터 조회)
            kafkaTemplate.getProducerFactory().createProducer();
            
            response.put("status", "UP ✅");
            response.put("kafka", "Connected");
            response.put("message", "Kafka 연결 성공");
            
            log.info("✅ Kafka 연결 테스트 성공");
            
        } catch (Exception e) {
            response.put("status", "DOWN ❌");
            response.put("kafka", "Disconnected");
            response.put("error", e.getMessage());
            log.error("❌ Kafka 연결 테스트 실패", e);
        }
        return response;
    }

    @GetMapping("/all")
    public Map<String, Object> checkAll() {
        Map<String, Object> response = new HashMap<>();
        
        // Application
        response.put("application", "UP ✅");
        response.put("module", "notification-module");
        response.put("port", serverPort);
        
        // Redis
        try {
            redisTemplate.opsForValue().set("health:check", "ok");
            response.put("redis", "UP ✅");
        } catch (Exception e) {
            response.put("redis", "DOWN ❌ - " + e.getMessage());
        }
        
        // Kafka
        try {
            kafkaTemplate.getProducerFactory().createProducer();
            response.put("kafka", "UP ✅");
        } catch (Exception e) {
            response.put("kafka", "DOWN ❌ - " + e.getMessage());
        }
        
        log.info("전체 헬스체크 완료");
        return response;
    }
}
