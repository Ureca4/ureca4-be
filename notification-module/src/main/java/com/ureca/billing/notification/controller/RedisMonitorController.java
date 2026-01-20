package com.ureca.billing.notification.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 모니터링 Controller
 * 
 * 중복 발송 방지 키 관리 및 모니터링
 */
@Tag(name = "5. Redis 모니터링", description = "Redis 중복방지 키 모니터링 API")
@RestController
@RequestMapping("/api/redis")
@RequiredArgsConstructor
public class RedisMonitorController {
    
    private final RedisTemplate<String, String> redisTemplate;
    
    // ========================================
    // 키 조회
    // ========================================
    
    @Operation(summary = "5-1. 중복방지 키 목록 조회", 
               description = "sent:msg:* 패턴의 모든 키 목록 조회")
    @GetMapping("/keys")
    public ResponseEntity<Map<String, Object>> getKeys(
            @Parameter(description = "타입 필터 (EMAIL, SMS, 비어있으면 전체)")
            @RequestParam(required = false) String type) {
        
        String pattern = type != null 
                ? "sent:msg:*:" + type 
                : "sent:msg:*";
        
        Set<String> keys = redisTemplate.keys(pattern);
        
        return ResponseEntity.ok(Map.of(
            "totalKeys", keys != null ? keys.size() : 0,
            "keyPattern", pattern,
            "keys", keys != null ? keys : Set.of()
        ));
    }
    
    @Operation(summary = "5-2. 특정 billId 중복 체크", 
               description = "해당 billId가 이미 발송되었는지 확인")
    @GetMapping("/check/{billId}")
    public ResponseEntity<Map<String, Object>> checkKey(
            @Parameter(description = "확인할 청구서 ID")
            @PathVariable Long billId,
            @Parameter(description = "알림 타입 (EMAIL, SMS)")
            @RequestParam(defaultValue = "EMAIL") String type) {
        
        String key = "sent:msg:" + billId + ":" + type;
        Boolean exists = redisTemplate.hasKey(key);
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        
        String status;
        if (Boolean.TRUE.equals(exists)) {
            status = "🔴 이미 발송됨 - 중복 발송 차단";
        } else {
            status = "🟢 발송 가능 - 중복 키 없음";
        }
        
        return ResponseEntity.ok(Map.of(
            "billId", billId,
            "type", type,
            "key", key,
            "exists", exists != null && exists,
            "isDuplicate", exists != null && exists,
            "ttl_seconds", ttl != null ? ttl : -2,
            "ttl_days", ttl != null && ttl > 0 ? ttl / 86400 : 0,
            "status", status
        ));
    }
    
    @Operation(summary = "5-3. 키 패턴별 개수 조회", 
               description = "EMAIL, SMS 등 타입별 키 개수 조회")
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getKeyStats() {
        Set<String> emailKeys = redisTemplate.keys("sent:msg:*:EMAIL");
        Set<String> smsKeys = redisTemplate.keys("sent:msg:*:SMS");
        Set<String> queueKeys = redisTemplate.keys("queue:*");
        Set<String> retryKeys = redisTemplate.keys("retry:msg:*");
        
        return ResponseEntity.ok(Map.of(
            "email", Map.of(
                "pattern", "sent:msg:*:EMAIL",
                "count", emailKeys != null ? emailKeys.size() : 0
            ),
            "sms", Map.of(
                "pattern", "sent:msg:*:SMS",
                "count", smsKeys != null ? smsKeys.size() : 0
            ),
            "retry", Map.of(
                "pattern", "retry:msg:*",
                "count", retryKeys != null ? retryKeys.size() : 0
            ),
            "queue", Map.of(
                "pattern", "queue:*",
                "count", queueKeys != null ? queueKeys.size() : 0
            ),
            "total", (emailKeys != null ? emailKeys.size() : 0) + 
                     (smsKeys != null ? smsKeys.size() : 0) +
                     (retryKeys != null ? retryKeys.size() : 0) +
                     (queueKeys != null ? queueKeys.size() : 0)
        ));
    }
    
    // ========================================
    // 키 관리 (테스트용)
    // ========================================
    
    @Operation(summary = "5-4. 중복방지 키 전체 삭제", 
               description = "sent:msg:* 패턴의 모든 키 삭제 (테스트용)")
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearKeys(
            @Parameter(description = "타입 필터 (EMAIL, SMS, 비어있으면 전체)")
            @RequestParam(required = false) String type) {
        
        String pattern = type != null 
                ? "sent:msg:*:" + type 
                : "sent:msg:*";
        
        Set<String> keys = redisTemplate.keys(pattern);
        
        int deletedCount = 0;
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            deletedCount = keys.size();
        }
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "🗑️ 중복방지 키가 초기화되었습니다.",
            "deletedCount", deletedCount,
            "pattern", pattern
        ));
    }
    
    @Operation(summary = "5-5. 특정 billId 키 삭제", 
               description = "특정 billId의 중복방지 키 삭제 (재발송 허용)")
    @DeleteMapping("/clear/{billId}")
    public ResponseEntity<Map<String, Object>> clearKey(
            @Parameter(description = "삭제할 청구서 ID")
            @PathVariable Long billId,
            @Parameter(description = "알림 타입 (EMAIL, SMS)")
            @RequestParam(defaultValue = "EMAIL") String type) {
        
        String key = "sent:msg:" + billId + ":" + type;
        Boolean deleted = redisTemplate.delete(key);
        
        return ResponseEntity.ok(Map.of(
            "success", deleted != null && deleted,
            "message", deleted != null && deleted 
                ? "✅ 키가 삭제되었습니다. 재발송 가능합니다."
                : "⚠️ 키가 존재하지 않습니다.",
            "billId", billId,
            "type", type,
            "key", key
        ));
    }
    
    @Operation(summary = "5-6. 수동으로 중복방지 키 생성", 
               description = "테스트용 중복방지 키 수동 생성")
    @PostMapping("/mark/{billId}")
    public ResponseEntity<Map<String, Object>> markAsSent(
            @Parameter(description = "마킹할 청구서 ID")
            @PathVariable Long billId,
            @Parameter(description = "알림 타입 (EMAIL, SMS)")
            @RequestParam(defaultValue = "EMAIL") String type,
            @Parameter(description = "TTL (일 단위, 기본 7일)")
            @RequestParam(defaultValue = "7") int ttlDays) {
        
        String key = "sent:msg:" + billId + ":" + type;
        redisTemplate.opsForValue().set(key, "sent", ttlDays, TimeUnit.DAYS);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "✅ 중복방지 키가 생성되었습니다.",
            "billId", billId,
            "type", type,
            "key", key,
            "ttl_days", ttlDays
        ));
    }
}