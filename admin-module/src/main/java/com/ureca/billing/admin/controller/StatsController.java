package com.ureca.billing.admin.controller;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Tag(name = "Admin - 통계", description = "전체 시스템 통계 조회 API")
@RestController
@RequestMapping("/api/admin/stats")
@RequiredArgsConstructor
@Slf4j
public class StatsController {

    private final JdbcTemplate jdbcTemplate;

    @Operation(summary = "전체 통계 조회", 
               description = "유저, 청구서, 알림 통계를 한 번에 조회")
    @GetMapping
    public ResponseEntity<Map<String, Object>> getStats() {
        long startTime = System.currentTimeMillis();
        log.info("📊 [StatsController] getStats() 요청 시작");
        
        try {
            Map<String, Object> stats = new HashMap<>();

            // 유저 통계
            long queryStart = System.currentTimeMillis();
            Integer totalUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM USERS", Integer.class);
            Integer activeUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM USERS WHERE status = 'ACTIVE'", Integer.class);
            log.debug("  └─ 유저 통계 쿼리 실행 시간: {}ms", System.currentTimeMillis() - queryStart);
            
            stats.put("totalUsers", totalUsers != null ? totalUsers : 0);
            stats.put("activeUsers", activeUsers != null ? activeUsers : 0);

            // 청구서 통계
            queryStart = System.currentTimeMillis();
            Integer totalBills = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM BILLS", Integer.class);
            
            String currentMonth = YearMonth.now().toString();
            Integer currentMonthBills = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM BILLS WHERE billing_month = ?", Integer.class, currentMonth);
            log.debug("  └─ 청구서 통계 쿼리 실행 시간: {}ms", System.currentTimeMillis() - queryStart);
            
            stats.put("totalBills", totalBills != null ? totalBills : 0);
            stats.put("currentMonthBills", currentMonthBills != null ? currentMonthBills : 0);

            // 알림 통계
            queryStart = System.currentTimeMillis();
            Integer totalNotifications = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM NOTIFICATIONS", Integer.class);
            Integer sentNotifications = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM NOTIFICATIONS WHERE notification_status = 'SENT'", Integer.class);
            Integer failedNotifications = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM NOTIFICATIONS WHERE notification_status = 'FAILED'", Integer.class);
            log.debug("  └─ 알림 통계 쿼리 실행 시간: {}ms", System.currentTimeMillis() - queryStart);
            
            stats.put("totalNotifications", totalNotifications != null ? totalNotifications : 0);
            stats.put("sentNotifications", sentNotifications != null ? sentNotifications : 0);
            stats.put("failedNotifications", failedNotifications != null ? failedNotifications : 0);

            // 알림 설정 통계
            queryStart = System.currentTimeMillis();
            Integer usersWithPrefs = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM USER_NOTIFICATION_PREFS", Integer.class);
            log.debug("  └─ 알림 설정 통계 쿼리 실행 시간: {}ms", System.currentTimeMillis() - queryStart);
            
            stats.put("usersWithPrefs", usersWithPrefs != null ? usersWithPrefs : 0);

            long totalTime = System.currentTimeMillis() - startTime;
            log.info("✅ [StatsController] getStats() 완료 - 총 처리 시간: {}ms", totalTime);
            
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("❌ [StatsController] getStats() 실패 - 처리 시간: {}ms, 에러: {}", totalTime, e.getMessage(), e);
            throw e;
        }
    }
}
