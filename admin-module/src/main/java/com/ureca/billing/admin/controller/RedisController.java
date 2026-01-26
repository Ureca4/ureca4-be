package com.ureca.billing.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "Admin - Redis 모니터링", description = "Redis 중복방지 키 모니터링 API")
@RestController
@RequestMapping("/api/admin/redis")
@RequiredArgsConstructor
@Slf4j
public class RedisController {

    private final RedisTemplate<String, String> redisTemplate;

    @Operation(summary = "Redis 키 통계", 
               description = "EMAIL, SMS, RETRY, QUEUE 패턴별 키 개수 조회 (SCAN 사용)")
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getKeyStats() {
        long startTime = System.currentTimeMillis();
        log.info("📊 [RedisController] getKeyStats() 요청 시작");
        
        try {
            // KEYS 대신 SCAN 사용 (비동기, 블로킹하지 않음)
            long scanStart = System.currentTimeMillis();
            long emailCount = countKeysByPattern("sent:msg:*:EMAIL");
            long smsCount = countKeysByPattern("sent:msg:*:SMS");
            long pushCount = countKeysByPattern("sent:msg:*:PUSH");
            long queueCount = countKeysByPattern("queue:*");
            long retryCount = countKeysByPattern("retry:msg:*");
            log.debug("  └─ Redis SCAN 실행 시간: {}ms", System.currentTimeMillis() - scanStart);
            
            Map<String, Object> result = new HashMap<>();
            result.put("email", Map.of(
                "pattern", "sent:msg:*:EMAIL",
                "count", emailCount
            ));
            result.put("sms", Map.of(
                "pattern", "sent:msg:*:SMS",
                "count", smsCount
            ));
            result.put("push", Map.of(
                "pattern", "sent:msg:*:PUSH",
                "count", pushCount
            ));
            result.put("retry", Map.of(
                "pattern", "retry:msg:*",
                "count", retryCount
            ));
            result.put("queue", Map.of(
                "pattern", "queue:*",
                "count", queueCount
            ));
            result.put("total", emailCount + smsCount + pushCount + retryCount + queueCount);
            
            long totalTime = System.currentTimeMillis() - startTime;
            log.info("✅ [RedisController] getKeyStats() 완료 - 총 처리 시간: {}ms, 총 키 개수: {}", 
                    totalTime, emailCount + smsCount + pushCount + retryCount + queueCount);
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("❌ [RedisController] getKeyStats() 실패 - 처리 시간: {}ms, 에러: {}", totalTime, e.getMessage(), e);
            // 에러 발생 시 빈 결과 반환
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("email", Map.of("pattern", "sent:msg:*:EMAIL", "count", 0));
            errorResult.put("sms", Map.of("pattern", "sent:msg:*:SMS", "count", 0));
            errorResult.put("push", Map.of("pattern", "sent:msg:*:PUSH", "count", 0));
            errorResult.put("retry", Map.of("pattern", "retry:msg:*", "count", 0));
            errorResult.put("queue", Map.of("pattern", "queue:*", "count", 0));
            errorResult.put("total", 0);
            return ResponseEntity.ok(errorResult);
        }
    }
    
    /**
     * SCAN을 사용하여 패턴에 맞는 키 개수 카운트
     * KEYS 명령보다 안전하고 블로킹하지 않음
     */
    private long countKeysByPattern(String pattern) {
        try {
            ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(100) // 한 번에 100개씩 스캔
                .build();
            
            long count = 0;
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    cursor.next();
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            log.warn("Redis SCAN 실패 - pattern: {}, error: {}", pattern, e.getMessage());
            return 0;
        }
    }
}
