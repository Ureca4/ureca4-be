package com.ureca.billing.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Admin - 알림 관리", description = "알림 상태 조회 API")
@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final JdbcTemplate jdbcTemplate;

    @Operation(summary = "알림 상태 요약", 
               description = "SENT/FAILED/PENDING/RETRY 상태별 개수 조회")
    @GetMapping("/status-summary")
    public ResponseEntity<Map<String, Object>> getStatusSummary() {
        long startTime = System.currentTimeMillis();
        log.info("📊 [NotificationController] getStatusSummary() 요청 시작");
        
        try {
            String sql = """
                SELECT notification_status, COUNT(*) as cnt
                FROM NOTIFICATIONS
                GROUP BY notification_status
                """;
            
            long queryStart = System.currentTimeMillis();
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
            log.debug("  └─ 상태 요약 쿼리 실행 시간: {}ms", System.currentTimeMillis() - queryStart);
            
            Map<String, Long> summary = new HashMap<>();
            summary.put("SENT", 0L);
            summary.put("FAILED", 0L);
            summary.put("RETRY", 0L);
            summary.put("PENDING", 0L);
            
            long total = 0;
            for (Map<String, Object> row : results) {
                String status = (String) row.get("notification_status");
                Long count = ((Number) row.get("cnt")).longValue();
                summary.put(status, count);
                total += count;
            }
            
            long totalTime = System.currentTimeMillis() - startTime;
            log.info("✅ [NotificationController] getStatusSummary() 완료 - 총 처리 시간: {}ms", totalTime);
            
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
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("❌ [NotificationController] getStatusSummary() 실패 - 처리 시간: {}ms, 에러: {}", totalTime, e.getMessage(), e);
            throw e;
        }
    }

    @Operation(summary = "실패 메시지 개수 조회", 
               description = "재시도 대상 FAILED 메시지 수")
    @GetMapping("/failed-count")
    public ResponseEntity<Map<String, Object>> getFailedCount() {
        String sql = """
            SELECT notification_id, retry_count
            FROM NOTIFICATIONS
            WHERE notification_status = 'FAILED'
            """;
        
        List<Map<String, Object>> failedMessages = jdbcTemplate.queryForList(sql);
        
        long retryableCount = failedMessages.stream()
                .mapToLong(row -> {
                    Integer retryCount = (Integer) row.get("retry_count");
                    return (retryCount != null && retryCount < 3) ? 1L : 0L;
                })
                .sum();
        
        long maxRetryReached = failedMessages.size() - retryableCount;
        
        return ResponseEntity.ok(Map.of(
            "totalFailed", failedMessages.size(),
            "retryable", retryableCount,
            "maxRetryReached", maxRetryReached,
            "message", String.format("재시도 가능: %d건, 최대 재시도 도달: %d건", 
                    retryableCount, maxRetryReached)
        ));
    }

    @Operation(summary = "실패 메시지 목록 조회", 
               description = "재시도 대상 메시지 상세 목록")
    @GetMapping("/failed-list")
    public ResponseEntity<Map<String, Object>> getFailedList(
            @Parameter(description = "조회할 최대 개수")
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        
        long startTime = System.currentTimeMillis();
        log.info("📊 [NotificationController] getFailedList() 요청 시작 - limit: {}", limit);
        
        try {
            String sql = """
                SELECT notification_id, user_id, bill_id, notification_status, 
                       retry_count, error_message, created_at
                FROM NOTIFICATIONS
                WHERE notification_status = 'FAILED'
                ORDER BY retry_count DESC, created_at DESC
                LIMIT ?
                """;
            
            long queryStart = System.currentTimeMillis();
            List<Map<String, Object>> messageList = jdbcTemplate.queryForList(sql, limit);
            log.debug("  └─ 실패 메시지 목록 쿼리 실행 시간: {}ms", System.currentTimeMillis() - queryStart);
            
            long totalTime = System.currentTimeMillis() - startTime;
            log.info("✅ [NotificationController] getFailedList() 완료 - 총 처리 시간: {}ms, 조회 건수: {}", totalTime, messageList.size());
            
            return ResponseEntity.ok(Map.of(
                "count", messageList.size(),
                "messages", messageList
            ));
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("❌ [NotificationController] getFailedList() 실패 - 처리 시간: {}ms, 에러: {}", totalTime, e.getMessage(), e);
            throw e;
        }
    }

    @Operation(summary = "24시간별 알림 통계 조회", 
               description = "최근 24시간 동안 시간별 SENT/FAILED 통계 및 실패율 조회")
    @GetMapping("/hourly-stats")
    public ResponseEntity<Map<String, Object>> getHourlyStats() {
        long startTime = System.currentTimeMillis();
        log.info("📊 [NotificationController] getHourlyStats() 요청 시작");
        
        try {
            // 최근 24시간 동안의 시간별 통계 조회
            String sql = """
                SELECT 
                    DATE_FORMAT(created_at, '%H:00') as hour,
                    HOUR(created_at) as hour_num,
                    SUM(CASE WHEN notification_status = 'SENT' THEN 1 ELSE 0 END) as sent,
                    SUM(CASE WHEN notification_status = 'FAILED' THEN 1 ELSE 0 END) as failed,
                    SUM(CASE WHEN notification_status = 'RETRY' THEN 1 ELSE 0 END) as retry,
                    SUM(CASE WHEN notification_status = 'PENDING' THEN 1 ELSE 0 END) as pending
                FROM NOTIFICATIONS
                WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
                GROUP BY DATE_FORMAT(created_at, '%H:00'), HOUR(created_at)
                ORDER BY hour_num
                """;
            
            long queryStart = System.currentTimeMillis();
            List<Map<String, Object>> hourlyData = jdbcTemplate.queryForList(sql);
            log.debug("  └─ 24시간 통계 쿼리 실행 시간: {}ms", System.currentTimeMillis() - queryStart);
            
            // 24시간 전체 데이터 생성 (없는 시간대는 0으로 채움)
            List<Map<String, Object>> fullDayData = new ArrayList<>();
            LocalTime now = LocalTime.now();
            int currentHour = now.getHour();
            
            // 시간별 데이터를 Map으로 변환 (빠른 조회를 위해)
            Map<String, Map<String, Object>> dataMap = new HashMap<>();
            for (Map<String, Object> row : hourlyData) {
                String hour = (String) row.get("hour");
                dataMap.put(hour, row);
            }
            
            // 최근 24시간 데이터 생성
            for (int i = 23; i >= 0; i--) {
                int targetHour = (currentHour - i + 24) % 24;
                String hourKey = String.format("%02d:00", targetHour);
                
                Map<String, Object> hourData = dataMap.getOrDefault(hourKey, new HashMap<>());
                long sent = hourData.containsKey("sent") ? ((Number) hourData.get("sent")).longValue() : 0L;
                long failed = hourData.containsKey("failed") ? ((Number) hourData.get("failed")).longValue() : 0L;
                long total = sent + failed;
                
                // 실패율 계산
                double failRate = total > 0 ? ((double) failed / total) * 100 : 0.0;
                
                // 금지 시간 체크 (22:00 ~ 08:00)
                boolean isBlockTime = targetHour >= 22 || targetHour < 8;
                
                Map<String, Object> result = new HashMap<>();
                result.put("time", hourKey);
                result.put("sent", sent);
                result.put("failed", failed);
                result.put("retry", hourData.containsKey("retry") ? ((Number) hourData.get("retry")).longValue() : 0L);
                result.put("pending", hourData.containsKey("pending") ? ((Number) hourData.get("pending")).longValue() : 0L);
                result.put("failRate", Math.round(failRate * 100.0) / 100.0); // 소수점 2자리
                result.put("isBlockTime", isBlockTime);
                
                fullDayData.add(result);
            }
            
            long totalTime = System.currentTimeMillis() - startTime;
            log.info("✅ [NotificationController] getHourlyStats() 완료 - 총 처리 시간: {}ms, 데이터 포인트: {}", totalTime, fullDayData.size());
            
            return ResponseEntity.ok(Map.of(
                "data", fullDayData,
                "period", "24 hours",
                "generatedAt", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("❌ [NotificationController] getHourlyStats() 실패 - 처리 시간: {}ms, 에러: {}", totalTime, e.getMessage(), e);
            throw e;
        }
    }
}
