package com.ureca.billing.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Tag(name = "Admin - 대기열 관리", description = "대기열 조회 및 처리 API")
@RestController
@RequestMapping("/api/admin/queue")
@RequiredArgsConstructor
@Slf4j
public class QueueController {

    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JdbcTemplate jdbcTemplate;

    @Operation(summary = "대기열 상태 조회", 
               description = "대기열 크기 및 발송 대기 메시지 수 조회")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        long startTime = System.currentTimeMillis();
        log.info("📊 [QueueController] getStatus() 요청 시작");
        
        try {
            String queueKey = "queue:message:waiting";
            
            long redisStart = System.currentTimeMillis();
            Long totalCount = redisTemplate.opsForZSet().size(queueKey);
            
            long now = System.currentTimeMillis() / 1000;
            Long readyCount = redisTemplate.opsForZSet().count(queueKey, 0, now);
            
            // score 기준으로 현재 시간 이하인 메시지만 조회 (발송 가능한 메시지)
            Set<String> readyMessages = redisTemplate.opsForZSet().rangeByScore(queueKey, 0, now, 0, 10);
            log.debug("  └─ Redis 조회 실행 시간: {}ms, readyCount: {}, totalCount: {}", 
                    System.currentTimeMillis() - redisStart, readyCount, totalCount);
            
            List<String> messageList = readyMessages != null 
                    ? new ArrayList<>(readyMessages)
                    : List.of();
            
            long totalTime = System.currentTimeMillis() - startTime;
            log.info("✅ [QueueController] getStatus() 완료 - 총 처리 시간: {}ms, totalCount: {}, readyCount: {}", 
                    totalTime, totalCount != null ? totalCount : 0, readyCount != null ? readyCount : 0);
            
            return ResponseEntity.ok(Map.of(
                "queueStatus", Map.of(
                    "totalCount", totalCount != null ? totalCount : 0,
                    "queueKey", queueKey,
                    "readyCount", readyCount != null ? readyCount : 0,
                    "readyMessages", messageList
                )
            ));
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("❌ [QueueController] getStatus() 실패 - 처리 시간: {}ms, 에러: {}", totalTime, e.getMessage(), e);
            throw e;
        }
    }

    @Operation(summary = "대기열 상세 정보", 
               description = "대기열의 상세 정보 및 현재 금지시간 상태")
    @GetMapping("/detail")
    public ResponseEntity<Map<String, Object>> getQueueDetail() {
        String queueKey = "queue:message:waiting";
        Long totalCount = redisTemplate.opsForZSet().size(queueKey);
        long now = System.currentTimeMillis() / 1000;
        Long readyCount = redisTemplate.opsForZSet().count(queueKey, 0, now);
        
        // 금지 시간 체크 (22:00 ~ 08:00)
        LocalTime currentTime = LocalTime.now();
        boolean isBlockTime = currentTime.isAfter(LocalTime.of(22, 0)) || currentTime.isBefore(LocalTime.of(8, 0));
        
        return ResponseEntity.ok(Map.of(
            "queueStatus", Map.of(
                "totalCount", totalCount != null ? totalCount : 0,
                "readyCount", readyCount != null ? readyCount : 0
            ),
            "isBlockTime", isBlockTime,
            "blockTimeMessage", isBlockTime 
                ? "⏰ 현재 금지 시간 (22:00~08:00) - 메시지 발송 유보 중" 
                : "✅ 정상 시간 - 대기열 메시지 발송 가능",
            "nextProcessTime", isBlockTime ? "08:00" : "즉시 처리 가능"
        ));
    }

    @Operation(summary = "대기열 수동 처리", 
               description = "대기열의 메시지를 즉시 Kafka로 재발행 (금지시간 무시)")
    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processQueue(
            @Parameter(description = "처리할 최대 개수")
            @RequestParam(name = "maxCount", defaultValue = "100") int maxCount) {
        
        String queueKey = "queue:message:waiting";
        Long beforeSize = redisTemplate.opsForZSet().size(queueKey);
        
        if (beforeSize == null || beforeSize == 0) {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "📭 대기열이 비어있습니다.",
                "processed", 0,
                "beforeSize", 0,
                "afterSize", 0
            ));
        }
        
        long now = System.currentTimeMillis() / 1000;
        Set<String> messages = redisTemplate.opsForZSet().rangeByScore(queueKey, 0, now, 0, maxCount);
        
        if (messages == null || messages.isEmpty()) {
            // Ready 메시지가 없으면 전체 조회 (강제 처리)
            messages = redisTemplate.opsForZSet().range(queueKey, 0, maxCount - 1);
        }
        
        int successCount = 0;
        int failCount = 0;
        
        for (String messageJson : messages) {
            try {
                kafkaTemplate.send("billing-event", messageJson);
                redisTemplate.opsForZSet().remove(queueKey, messageJson);
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.error("❌ Failed to process message: {}", e.getMessage());
            }
        }
        
        Long afterSize = redisTemplate.opsForZSet().size(queueKey);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", String.format("✅ 대기열 처리 완료. %d건 성공, %d건 실패", successCount, failCount),
            "processed", successCount,
            "failed", failCount,
            "beforeSize", beforeSize != null ? beforeSize : 0,
            "afterSize", afterSize != null ? afterSize : 0
        ));
    }

    @Operation(summary = "대기열 초기화", 
               description = "대기열의 모든 메시지 삭제 (테스트용)")
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearQueue() {
        String queueKey = "queue:message:waiting";
        Long beforeSize = redisTemplate.opsForZSet().size(queueKey);
        redisTemplate.delete(queueKey);
        Long afterSize = redisTemplate.opsForZSet().size(queueKey);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "🗑️ 대기열이 초기화되었습니다.",
            "deletedCount", beforeSize != null ? beforeSize : 0,
            "currentSize", afterSize != null ? afterSize : 0
        ));
    }
}
