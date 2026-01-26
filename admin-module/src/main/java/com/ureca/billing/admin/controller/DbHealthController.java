package com.ureca.billing.admin.controller;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Tag(name = "Admin - DB 상태", description = "DB 연결 풀 상태 모니터링 API")
@RestController
@RequestMapping("/api/admin/db-health")
@RequiredArgsConstructor
@Slf4j
public class DbHealthController {

    private final DataSource dataSource;

    @Operation(summary = "DB 연결 풀 상태 조회", 
               description = "HikariCP 연결 풀의 현재 상태 및 메트릭 조회")
    @GetMapping
    public ResponseEntity<Map<String, Object>> getDbHealth() {
        Map<String, Object> health = new HashMap<>();
        
        try {
            if (dataSource instanceof HikariDataSource) {
                HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
                HikariPoolMXBean poolBean = hikariDataSource.getHikariPoolMXBean();
                
                health.put("poolName", hikariDataSource.getPoolName());
                health.put("maximumPoolSize", hikariDataSource.getMaximumPoolSize());
                health.put("minimumIdle", hikariDataSource.getMinimumIdle());
                health.put("activeConnections", poolBean != null ? poolBean.getActiveConnections() : -1);
                health.put("idleConnections", poolBean != null ? poolBean.getIdleConnections() : -1);
                health.put("totalConnections", poolBean != null ? poolBean.getTotalConnections() : -1);
                health.put("threadsAwaitingConnection", poolBean != null ? poolBean.getThreadsAwaitingConnection() : -1);
                health.put("connectionTimeout", hikariDataSource.getConnectionTimeout());
                
                // 연결 풀 사용률 계산
                int maxPool = hikariDataSource.getMaximumPoolSize();
                int active = poolBean != null ? poolBean.getActiveConnections() : 0;
                double usagePercent = maxPool > 0 ? (double) active / maxPool * 100 : 0;
                health.put("usagePercent", String.format("%.2f", usagePercent));
                
                // 경고 상태
                boolean isHealthy = poolBean != null && 
                    poolBean.getThreadsAwaitingConnection() == 0 && 
                    usagePercent < 80;
                health.put("isHealthy", isHealthy);
                health.put("status", isHealthy ? "HEALTHY" : "WARNING");
                
                if (usagePercent >= 80) {
                    health.put("message", "연결 풀 사용률이 80% 이상입니다. 연결 풀 크기를 늘려야 할 수 있습니다.");
                } else if (poolBean != null && poolBean.getThreadsAwaitingConnection() > 0) {
                    health.put("message", "연결 대기 중인 스레드가 있습니다. 연결 풀이 부족할 수 있습니다.");
                } else {
                    health.put("message", "정상");
                }
            } else {
                health.put("status", "UNKNOWN");
                health.put("message", "HikariCP DataSource가 아닙니다.");
            }
        } catch (Exception e) {
            log.error("DB 연결 풀 상태 조회 실패", e);
            health.put("status", "ERROR");
            health.put("message", "연결 풀 상태 조회 실패: " + e.getMessage());
        }
        
        return ResponseEntity.ok(health);
    }
}
