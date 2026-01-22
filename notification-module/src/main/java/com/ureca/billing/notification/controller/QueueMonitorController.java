package com.ureca.billing.notification.controller;

import java.util.Map;

import java.util.Set;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.domain.dto.WaitingQueueStatus;
import com.ureca.billing.notification.service.MessagePolicyService;
import com.ureca.billing.notification.service.WaitingQueueService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 대기열 모니터링 및 관리 Controller
 * 
 * 기존 QueueMonitorController + SchedulerTestController 통합
 * - 대기열 상태 조회
 * - 대기열 메시지 관리
 * - 대기열 초기화
 */
@Tag(name = "4. 대기열 모니터링", description = "금지시간 대기열 모니터링 및 관리 API")
@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
@Slf4j
public class QueueMonitorController {
    
    private final WaitingQueueService queueService;
    private final MessagePolicyService policyService;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;      
    private final RedisTemplate<String, String> redisTemplate; 
    
    // ========================================
    // 대기열 상태 조회
    // ========================================
    
    @Operation(summary = "4-1. 대기열 상태 조회", 
               description = "대기열 크기 및 발송 대기 메시지 수 조회")
    @GetMapping("/status")
    public ResponseEntity<WaitingQueueStatus> getStatus() {
        WaitingQueueStatus status = queueService.getQueueStatus();
        return ResponseEntity.ok(status);
    }
    
    @Operation(summary = "4-2. 발송 가능 메시지 조회", 
               description = "금지시간 해제 후 발송 가능한 메시지 목록")
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> getReadyMessages(
            @Parameter(description = "조회할 최대 개수")
            @RequestParam(defaultValue = "10") int limit) {
        
        Set<String> messages = queueService.getReadyMessages(limit);
        
        return ResponseEntity.ok(Map.of(
            "totalReady", messages != null ? messages.size() : 0,
            "messages", messages != null ? messages : Set.of(),
            "isBlockTime", policyService.isBlockTime()
        ));
    }
    
    @Operation(summary = "4-3. 대기열 상세 정보", 
               description = "대기열의 상세 정보 및 현재 금지시간 상태")
    @GetMapping("/detail")
    public ResponseEntity<Map<String, Object>> getQueueDetail() {
        WaitingQueueStatus status = queueService.getQueueStatus();
        boolean isBlockTime = policyService.isBlockTime();
        
        return ResponseEntity.ok(Map.of(
            "queueStatus", status,
            "isBlockTime", isBlockTime,
            "blockTimeMessage", isBlockTime 
                ? "⏰ 현재 금지 시간 (22:00~08:00) - 메시지 발송 유보 중" 
                : "✅ 정상 시간 - 대기열 메시지 발송 가능",
            "nextProcessTime", isBlockTime ? "08:00" : "즉시 처리 가능"
        ));
    }
    
 // ========================================
 // 대기열 수동 처리
 // ========================================

 @Operation(summary = "4-7. 대기열 수동 처리", 
            description = "대기열의 메시지를 즉시 Kafka로 재발행 (금지시간 무시)")
 @PostMapping("/process")
 public ResponseEntity<Map<String, Object>> processQueue(
         @Parameter(description = "처리할 최대 개수")
         @RequestParam(defaultValue = "100") int maxCount) {
     
     long beforeSize = queueService.getQueueSize();
     
     if (beforeSize == 0) {
         return ResponseEntity.ok(Map.of(
             "success", true,
             "message", "📭 대기열이 비어있습니다.",
             "processed", 0,
             "beforeSize", 0,
             "afterSize", 0
         ));
     }
     
     Set<String> messages = queueService.getReadyMessages(maxCount);
     
     if (messages == null || messages.isEmpty()) {
         // Ready 메시지가 없으면 전체 조회 (강제 처리)
         messages = redisTemplate.opsForZSet().range("queue:message:waiting", 0, maxCount - 1);
     }
     
     int successCount = 0;
     int failCount = 0;
     
     for (String messageJson : messages) {
         try {
             kafkaTemplate.send("billing-event", messageJson);
             queueService.removeFromQueue(messageJson);
             successCount++;
         } catch (Exception e) {
             failCount++;
             log.error("❌ Failed to process message: {}", e.getMessage());
         }
     }
     
     return ResponseEntity.ok(Map.of(
         "success", true,
         "message", String.format("✅ 대기열 처리 완료. %d건 성공, %d건 실패", successCount, failCount),
         "processed", successCount,
         "failed", failCount,
         "beforeSize", beforeSize,
         "afterSize", queueService.getQueueSize()
     ));
 }
    
    // ========================================
    // 대기열 메시지 관리
    // ========================================
    
    @Operation(summary = "4-4. 대기열에 메시지 수동 추가", 
               description = "테스트용 메시지를 대기열에 추가")
    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> addMessage(@RequestBody BillingMessageDto message) {
        try {
            queueService.addToQueue(message);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "✅ 메시지가 대기열에 추가되었습니다.",
                "billId", message.getBillId(),
                "queueSize", queueService.getQueueSize()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    @Operation(summary = "4-5. 대기열 초기화", 
               description = "대기열의 모든 메시지 삭제 (테스트용)")
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearQueue() {
        long beforeSize = queueService.getQueueSize();
        queueService.clearQueue();
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "🗑️ 대기열이 초기화되었습니다.",
            "deletedCount", beforeSize,
            "currentSize", queueService.getQueueSize()
        ));
    }
    
    // ========================================
    // 스케줄러 상태 (기존 SchedulerTestController에서 이동)
    // ========================================
    
    @Operation(summary = "4-6. 스케줄러 상태 확인", 
               description = "대기열 처리 스케줄러 상태 및 다음 실행 시간")
    @GetMapping("/scheduler-status")
    public ResponseEntity<Map<String, Object>> getSchedulerStatus() {
        return ResponseEntity.ok(Map.of(
            "queueSize", queueService.getQueueSize(),
            "isBlockTime", policyService.isBlockTime(),
            "scheduledProcessTime", "매일 08:00 (금지 시간 해제 시)",
            "testScheduler", "매 1분마다 (개발 환경)",
            "status", queueService.getQueueStatus()
        ));
    }
}