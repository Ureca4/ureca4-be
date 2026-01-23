package com.ureca.billing.notification.controller;

import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.service.ScheduledQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 예약 발송 모니터링 Controller
 * 
 * - 예약 현황 조회
 * - 예약 취소/변경
 * - 테스트용 수동 예약
 */
@Tag(name = "6. 예약 발송 모니터링", description = "예약 발송 현황 조회 및 관리 API")
@RestController
@RequestMapping("/api/scheduled")
@RequiredArgsConstructor
@Slf4j
public class ScheduledMessageController {
    
    private final ScheduledQueueService scheduledQueueService;
    
    // ========================================
    // 예약 현황 조회
    // ========================================
    
    @Operation(summary = "6-1. 예약 통계", 
               description = "채널별 예약 건수 및 발송 대기 건수")
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Long> stats = scheduledQueueService.getQueueStats();
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "timestamp", LocalDateTime.now().toString(),
            "stats", Map.of(
                "email", stats.get("EMAIL"),
                "sms", stats.get("SMS"),
                "push", stats.get("PUSH"),
                "total", stats.get("ALL"),
                "readyToSend", stats.get("READY")
            ),
            "description", Map.of(
                "total", "전체 예약 건수",
                "readyToSend", "발송 시간 도래한 건수 (다음 스케줄러 실행 시 처리)"
            )
        ));
    }
    
    @Operation(summary = "6-2. 전체 예약 목록", 
               description = "전체 예약 발송 목록 (페이징)")
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> getAllSchedules(
            @Parameter(description = "시작 위치") @RequestParam(defaultValue = "0") int offset,
            @Parameter(description = "조회 개수") @RequestParam(defaultValue = "20") int limit) {
        
        List<Map<String, Object>> schedules = scheduledQueueService.getAllSchedules(offset, limit);
        Map<String, Long> stats = scheduledQueueService.getQueueStats();
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "offset", offset,
            "limit", limit,
            "count", schedules.size(),
            "totalScheduled", stats.get("ALL"),
            "schedules", schedules
        ));
    }
    
    @Operation(summary = "6-3. 사용자별 예약 목록", 
               description = "특정 사용자의 예약 발송 목록")
    @GetMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> getUserSchedules(@PathVariable Long userId) {
        
        List<Map<String, Object>> schedules = scheduledQueueService.getUserSchedules(userId);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "userId", userId,
            "count", schedules.size(),
            "schedules", schedules
        ));
    }
    
    @Operation(summary = "6-4. 발송 대기 메시지 조회", 
               description = "현재 시간 기준 발송 가능한 메시지 목록")
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> getReadyMessages(
            @Parameter(description = "채널 (EMAIL, SMS, ALL)") 
            @RequestParam(defaultValue = "ALL") String channel,
            @Parameter(description = "최대 개수") 
            @RequestParam(defaultValue = "10") int limit) {
        
        List<BillingMessageDto> ready = scheduledQueueService.getReadyMessages(channel, limit);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "channel", channel,
            "count", ready.size(),
            "messages", ready,
            "note", "이 메시지들은 다음 스케줄러 실행 시 처리됩니다."
        ));
    }
    
    // ========================================
    // 예약 취소/변경
    // ========================================
    
    @Operation(summary = "6-5. 청구서 예약 취소", 
               description = "특정 청구서의 예약 발송 취소")
    @DeleteMapping("/bill/{billId}")
    public ResponseEntity<Map<String, Object>> cancelByBillId(
            @PathVariable Long billId,
            @Parameter(description = "채널") @RequestParam(defaultValue = "EMAIL") String channel) {
        
        boolean cancelled = scheduledQueueService.cancelByBillId(billId, channel);
        
        return ResponseEntity.ok(Map.of(
            "success", cancelled,
            "message", cancelled 
                    ? "🚫 예약이 취소되었습니다." 
                    : "⚠️ 예약을 찾을 수 없습니다.",
            "billId", billId,
            "channel", channel
        ));
    }
    
    @Operation(summary = "6-6. 사용자 예약 전체 취소", 
               description = "특정 사용자의 모든 예약 취소")
    @DeleteMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> cancelByUserId(@PathVariable Long userId) {
        
        int cancelledCount = scheduledQueueService.cancelByUserId(userId);
        
        return ResponseEntity.ok(Map.of(
            "success", cancelledCount > 0,
            "message", cancelledCount > 0 
                    ? String.format("🚫 %d건의 예약이 취소되었습니다.", cancelledCount)
                    : "⚠️ 취소할 예약이 없습니다.",
            "userId", userId,
            "cancelledCount", cancelledCount
        ));
    }
    
    @Operation(summary = "6-7. 예약 시간 변경", 
               description = "특정 청구서의 예약 시간 변경")
    @PutMapping("/bill/{billId}/reschedule")
    public ResponseEntity<Map<String, Object>> reschedule(
            @PathVariable Long billId,
            @Parameter(description = "채널") @RequestParam(defaultValue = "EMAIL") String channel,
            @Parameter(description = "새 예약 시간") 
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime newScheduledAt) {
        
        // 과거 시간 체크
        if (newScheduledAt.isBefore(LocalDateTime.now())) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "예약 시간은 현재 시간 이후여야 합니다."
            ));
        }
        
        boolean rescheduled = scheduledQueueService.reschedule(billId, channel, newScheduledAt);
        
        return ResponseEntity.ok(Map.of(
            "success", rescheduled,
            "message", rescheduled 
                    ? "✅ 예약 시간이 변경되었습니다." 
                    : "⚠️ 예약을 찾을 수 없습니다.",
            "billId", billId,
            "channel", channel,
            "newScheduledAt", newScheduledAt.toString()
        ));
    }
    
    // ========================================
    // 테스트/관리용
    // ========================================
    
    @Operation(summary = "6-8. 수동 예약 등록", 
               description = "테스트용 수동 예약 등록")
    @PostMapping("/manual")
    public ResponseEntity<Map<String, Object>> manualSchedule(
            @RequestBody BillingMessageDto message,
            @Parameter(description = "발송 예약 시간") 
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime scheduledAt,
            @Parameter(description = "채널") @RequestParam(defaultValue = "EMAIL") String channel) {
        
        // 과거 시간 체크
        if (scheduledAt.isBefore(LocalDateTime.now())) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "예약 시간은 현재 시간 이후여야 합니다."
            ));
        }
        
        scheduledQueueService.schedule(message, scheduledAt, channel);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "✅ 수동 예약이 등록되었습니다.",
            "billId", message.getBillId(),
            "userId", message.getUserId(),
            "channel", channel,
            "scheduledAt", scheduledAt.toString()
        ));
    }
    
    @Operation(summary = "6-9. 예약 큐 초기화", 
               description = "모든 예약 삭제 (테스트용)")
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearAll() {
        Map<String, Long> statsBefore = scheduledQueueService.getQueueStats();
        long totalBefore = statsBefore.get("ALL");
        
        scheduledQueueService.clearAll();
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "🗑️ 모든 예약이 삭제되었습니다.",
            "deletedCount", totalBefore
        ));
    }
}