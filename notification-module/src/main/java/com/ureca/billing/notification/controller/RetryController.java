package com.ureca.billing.notification.controller;

import com.ureca.billing.notification.domain.entity.Notification;
import com.ureca.billing.notification.domain.repository.NotificationRepository;
import com.ureca.billing.notification.service.RetryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * 재시도 및 DLT 관리 Controller
 * 
 * - FAILED 메시지 재시도
 * - Notification 상태 모니터링
 * - DLT 메시지 관리
 */
@Tag(name = "6. 재시도/DLT 관리", description = "실패 메시지 재시도 및 DLT 관리 API")
@RestController
@RequestMapping("/api/retry")
@RequiredArgsConstructor
public class RetryController {
    
    private final RetryService retryService;
    private final NotificationRepository notificationRepository;
    
    // ========================================
    // 상태 조회
    // ========================================
    
    @Operation(summary = "6-1. Notification 상태 요약", 
               description = "SENT/FAILED/PENDING/RETRY 상태별 개수 조회")
    @GetMapping("/status-summary")
    public ResponseEntity<Map<String, Object>> getStatusSummary() {
        Iterable<Notification> allNotifications = notificationRepository.findAll();
        
        Map<String, Long> summary = new HashMap<>();
        summary.put("SENT", 0L);
        summary.put("FAILED", 0L);
        summary.put("RETRY", 0L);
        summary.put("PENDING", 0L);
        
        long total = 0;
        for (Notification notification : allNotifications) {
            String status = notification.getNotificationStatus();
            summary.merge(status, 1L, Long::sum);
            total++;
        }
        
        return ResponseEntity.ok(Map.of(
            "summary", summary,
            "total", total,
            "description", Map.of(
                "SENT", "발송 완료",
                "FAILED", "발송 실패 (재시도 대상)",
                "RETRY", "재시도 중",
                "PENDING", "대기 중 (금지시간)"
            )
        ));
    }
    
    @Operation(summary = "6-2. FAILED 메시지 개수 조회", 
               description = "재시도 대상 FAILED 메시지 수")
    @GetMapping("/failed-count")
    public ResponseEntity<Map<String, Object>> getFailedCount() {
        List<Notification> failedMessages = notificationRepository.findFailedMessagesForRetry();
        
        long retryableCount = failedMessages.stream()
                .filter(n -> n.getRetryCount() < 3)
                .count();
        
        long maxRetryReached = failedMessages.size() - retryableCount;
        
        return ResponseEntity.ok(Map.of(
            "totalFailed", failedMessages.size(),
            "retryable", retryableCount,
            "maxRetryReached", maxRetryReached,
            "message", String.format("재시도 가능: %d건, 최대 재시도 도달: %d건", 
                    retryableCount, maxRetryReached)
        ));
    }
    
    @Operation(summary = "6-3. FAILED 메시지 목록 조회", 
               description = "재시도 대상 메시지 상세 목록")
    @GetMapping("/failed-list")
    public ResponseEntity<Map<String, Object>> getFailedList(
            @Parameter(description = "조회할 최대 개수")
            @RequestParam(defaultValue = "20") int limit) {
        
        List<Notification> failedMessages = notificationRepository.findFailedMessagesForRetry()
                .stream()
                .limit(limit)
                .toList();
        
        List<Map<String, Object>> messageList = failedMessages.stream()
                .map(n -> Map.<String, Object>of(
                    "notificationId", n.getNotificationId(),
                    "userId", n.getUserId(),
                    "status", n.getNotificationStatus(),
                    "retryCount", n.getRetryCount(),
                    "errorMessage", n.getErrorMessage() != null ? n.getErrorMessage() : "N/A",
                    "createdAt", n.getCreatedAt().toString()
                ))
                .toList();
        
        return ResponseEntity.ok(Map.of(
            "count", messageList.size(),
            "messages", messageList
        ));
    }
    
    // ========================================
    // 재시도 실행
    // ========================================
    
    @Operation(summary = "6-4. 재시도 스케줄러 수동 실행", 
               description = "FAILED 상태 메시지를 수동으로 재시도")
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runRetryManually(
            @Parameter(description = "최대 처리 개수")
            @RequestParam(defaultValue = "100") int maxCount) {
        
        // 재시도 전 상태 확인
        List<Notification> beforeRetry = notificationRepository.findFailedMessagesForRetry();
        int beforeCount = beforeRetry.size();
        
        // 재시도 실행
        int retryCount = retryService.retryFailedMessages(maxCount);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "beforeCount", beforeCount,
            "retriedCount", retryCount,
            "message", String.format("✅ 재시도 완료. %d건 중 %d건 처리", beforeCount, retryCount)
        ));
    }
    
    @Operation(summary = "6-5. 특정 Notification 재시도", 
               description = "특정 notificationId의 메시지만 재시도")
    @PostMapping("/run/{notificationId}")
    public ResponseEntity<Map<String, Object>> retrySingle(
            @Parameter(description = "재시도할 Notification ID")
            @PathVariable Long notificationId) {
        
        return notificationRepository.findById(notificationId)
                .map(notification -> {
                    if (!"FAILED".equals(notification.getNotificationStatus())) {
                        return ResponseEntity.badRequest().body(Map.<String, Object>of(
                            "success", false,
                            "message", "⚠️ FAILED 상태가 아닙니다. 현재 상태: " + notification.getNotificationStatus()
                        ));
                    }
                    
                    if (notification.getRetryCount() >= 3) {
                        return ResponseEntity.badRequest().body(Map.<String, Object>of(
                            "success", false,
                            "message", "⚠️ 최대 재시도 횟수(3회)에 도달했습니다."
                        ));
                    }
                    
                    // 재시도 실행 (단건)
                    int result = retryService.retryFailedMessages(1);
                    
                    return ResponseEntity.ok(Map.<String, Object>of(
                        "success", result > 0,
                        "notificationId", notificationId,
                        "message", result > 0 ? "✅ 재시도 요청 완료" : "⚠️ 재시도 실패"
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    // ========================================
    // DLT 관리
    // ========================================
    
    @Operation(summary = "6-6. 최대 재시도 도달 메시지 조회", 
               description = "3회 재시도 후 최종 실패한 메시지 목록 (DLT 대상)")
    @GetMapping("/dlt-candidates")
    public ResponseEntity<Map<String, Object>> getDltCandidates(
            @Parameter(description = "조회할 최대 개수")
            @RequestParam(defaultValue = "20") int limit) {
        
        Iterable<Notification> all = notificationRepository.findAll();
        
        List<Map<String, Object>> dltCandidates = StreamSupport.stream(all.spliterator(), false)
                .filter(n -> "FAILED".equals(n.getNotificationStatus()) && n.getRetryCount() >= 3)
                .limit(limit)
                .map(n -> Map.<String, Object>of(
                    "notificationId", n.getNotificationId(),
                    "userId", n.getUserId(),
                    "retryCount", n.getRetryCount(),
                    "errorMessage", n.getErrorMessage() != null ? n.getErrorMessage() : "N/A",
                    "createdAt", n.getCreatedAt().toString()
                ))
                .toList();
        
        return ResponseEntity.ok(Map.of(
            "count", dltCandidates.size(),
            "description", "3회 재시도 후 최종 실패한 메시지 (수동 처리 필요)",
            "messages", dltCandidates
        ));
    }
}