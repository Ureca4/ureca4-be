package com.ureca.billing.admin.controller;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Admin - 시스템 헬스체크", description = "전체 시스템 헬스체크 API")
@RestController
@RequestMapping("/api/admin/system-health")
@RequiredArgsConstructor
@Slf4j
public class SystemHealthController {

    private final DataSource dataSource;

    @Value("${app.notification.base-url:http://localhost:8081}")
    private String notificationBaseUrl;

    @Value("${app.batch.base-url:http://localhost:8089}")
    private String batchBaseUrl;

    @Operation(summary = "전체 시스템 헬스체크", 
               description = "DB, Batch Module, Notification Module 상태 조회")
    @GetMapping
    public ResponseEntity<Map<String, Object>> getSystemHealth() {
        long startTime = System.currentTimeMillis();
        log.info("📊 [SystemHealthController] getSystemHealth() 요청 시작");
        
        Map<String, Object> health = new HashMap<>();
        List<Map<String, Object>> services = new ArrayList<>();
        
        // 1. DB 상태 체크
        Map<String, Object> dbHealth = checkDatabaseHealth();
        services.add(dbHealth);
        
        // 2. Batch Module 헬스체크
        Map<String, Object> batchHealth = checkBatchModuleHealth();
        services.add(batchHealth);
        
        // 3. Notification Module 헬스체크 (여러 인스턴스)
        List<Map<String, Object>> notificationHealths = checkNotificationModuleHealths();
        services.addAll(notificationHealths);
        
        // 전체 상태 계산
        boolean allHealthy = services.stream()
            .allMatch(s -> "UP".equals(s.get("status")) || "HEALTHY".equals(s.get("status")));
        
        health.put("status", allHealthy ? "HEALTHY" : "DEGRADED");
        health.put("services", services);
        health.put("totalServices", services.size());
        health.put("healthyServices", services.stream()
            .filter(s -> "UP".equals(s.get("status")) || "HEALTHY".equals(s.get("status")))
            .count());
        
        long totalTime = System.currentTimeMillis() - startTime;
        health.put("checkTime", totalTime);
        log.info("✅ [SystemHealthController] getSystemHealth() 완료 - 총 처리 시간: {}ms", totalTime);
        
        return ResponseEntity.ok(health);
    }

    private Map<String, Object> checkDatabaseHealth() {
        Map<String, Object> dbHealth = new HashMap<>();
        dbHealth.put("name", "Database");
        dbHealth.put("type", "DATABASE");
        
        try {
            if (dataSource instanceof HikariDataSource) {
                HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
                HikariPoolMXBean poolBean = hikariDataSource.getHikariPoolMXBean();
                
                int maxPool = hikariDataSource.getMaximumPoolSize();
                int active = poolBean != null ? poolBean.getActiveConnections() : 0;
                int idle = poolBean != null ? poolBean.getIdleConnections() : 0;
                int total = poolBean != null ? poolBean.getTotalConnections() : 0;
                int waiting = poolBean != null ? poolBean.getThreadsAwaitingConnection() : 0;
                double usagePercent = maxPool > 0 ? (double) active / maxPool * 100 : 0;
                
                dbHealth.put("status", "HEALTHY");
                dbHealth.put("poolName", hikariDataSource.getPoolName());
                dbHealth.put("maximumPoolSize", maxPool);
                dbHealth.put("activeConnections", active);
                dbHealth.put("idleConnections", idle);
                dbHealth.put("totalConnections", total);
                dbHealth.put("threadsAwaitingConnection", waiting);
                dbHealth.put("usagePercent", String.format("%.2f", usagePercent));
                dbHealth.put("message", usagePercent >= 80 ? "연결 풀 사용률이 높습니다" : "정상");
            } else {
                dbHealth.put("status", "UNKNOWN");
                dbHealth.put("message", "HikariCP DataSource가 아닙니다");
            }
        } catch (Exception e) {
            log.error("DB 헬스체크 실패", e);
            dbHealth.put("status", "DOWN");
            dbHealth.put("message", "연결 실패: " + e.getMessage());
        }
        
        return dbHealth;
    }

    private Map<String, Object> checkBatchModuleHealth() {
        Map<String, Object> batchHealth = new HashMap<>();
        batchHealth.put("name", "Batch Module");
        batchHealth.put("type", "BATCH_MODULE");
        batchHealth.put("url", batchBaseUrl);
        
        try {
            RestTemplate restTemplate = new RestTemplate();
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(3000);
            factory.setReadTimeout(3000);
            restTemplate.setRequestFactory(factory);
            
            String healthUrl = batchBaseUrl + "/health";
            Map<String, Object> response = restTemplate.getForObject(healthUrl, Map.class);
            
            if (response != null && "UP".equals(response.get("status"))) {
                batchHealth.put("status", "UP");
                batchHealth.put("message", response.get("message") != null ? response.get("message") : "정상");
            } else {
                batchHealth.put("status", "DOWN");
                batchHealth.put("message", "응답이 비정상입니다");
            }
        } catch (Exception e) {
            log.error("Batch Module 헬스체크 실패: {}", e.getMessage());
            batchHealth.put("status", "DOWN");
            batchHealth.put("message", "연결 실패: " + e.getMessage());
        }
        
        return batchHealth;
    }

    private List<Map<String, Object>> checkNotificationModuleHealths() {
        List<Map<String, Object>> healths = new ArrayList<>();
        
        // Notification Module 인스턴스들 (기본 1개, 필요시 확장 가능)
        String[] notificationUrls = {
            notificationBaseUrl,
            // 필요시 추가 인스턴스 URL 추가
            // "http://localhost:8083",
            // "http://localhost:8084",
            // "http://localhost:8085",
        };
        
        for (int i = 0; i < notificationUrls.length; i++) {
            Map<String, Object> notificationHealth = new HashMap<>();
            String url = notificationUrls[i];
            String instanceName = notificationUrls.length > 1 ? "Notification Module #" + (i + 1) : "Notification Module";
            
            notificationHealth.put("name", instanceName);
            notificationHealth.put("type", "NOTIFICATION_MODULE");
            notificationHealth.put("url", url);
            
            try {
                RestTemplate restTemplate = new RestTemplate();
                SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(3000);
                factory.setReadTimeout(3000);
                restTemplate.setRequestFactory(factory);
                
                String healthUrl = url + "/health";
                Map<String, Object> response = restTemplate.getForObject(healthUrl, Map.class);
                
                if (response != null && "UP".equals(response.get("status"))) {
                    notificationHealth.put("status", "UP");
                    notificationHealth.put("message", response.get("message") != null ? response.get("message") : "정상");
                } else {
                    notificationHealth.put("status", "DOWN");
                    notificationHealth.put("message", "응답이 비정상입니다");
                }
            } catch (Exception e) {
                log.error("Notification Module 헬스체크 실패 [{}]: {}", url, e.getMessage());
                notificationHealth.put("status", "DOWN");
                notificationHealth.put("message", "연결 실패: " + e.getMessage());
            }
            
            healths.add(notificationHealth);
        }
        
        return healths;
    }
}
