package com.ureca.billing.notification.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 실제 DB 데이터 조회 Controller
 * 
 * - 실제 유저 목록/상세 조회
 * - 실제 청구서 목록/상세 조회
 * - 유저별 청구서 조회
 * 
 * ⚠️ 테스트용 - 실제 프로덕션에서는 권한 체크 필요
 */
@Tag(name = "8. 실제 데이터 조회", description = "DB의 실제 유저/청구서 데이터 조회 API")
@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
@Slf4j
public class DataController {
    
    private final JdbcTemplate jdbcTemplate;
    
    // ========================================
    // 유저 조회
    // ========================================
    
    @Operation(summary = "8-1. 유저 목록 조회", 
               description = "실제 DB의 유저 목록 조회 (페이징, 상태 필터)")
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> getUsers(
            @Parameter(description = "페이지 번호 (0부터 시작)") 
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기") 
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "상태 필터 (ACTIVE, SUSPENDED, WITHDRAWN)") 
            @RequestParam(required = false) String status) {
        
        int offset = page * size;
        
        // 전체 개수 조회
        String countSql = "SELECT COUNT(*) FROM USERS" + 
            (status != null ? " WHERE status = ?" : "");
        
        Integer totalCount = status != null 
            ? jdbcTemplate.queryForObject(countSql, Integer.class, status)
            : jdbcTemplate.queryForObject(countSql, Integer.class);
        
        // 유저 목록 조회
        String sql = """
            SELECT user_id, name, status, created_at
            FROM USERS
            """ + (status != null ? " WHERE status = ? " : "") + """
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """;
        
        List<Map<String, Object>> users = status != null
            ? jdbcTemplate.queryForList(sql, status, size, offset)
            : jdbcTemplate.queryForList(sql, size, offset);
        
        return ResponseEntity.ok(Map.of(
            "totalCount", totalCount != null ? totalCount : 0,
            "page", page,
            "size", size,
            "totalPages", (totalCount != null ? totalCount : 0) / size + 1,
            "users", users
        ));
    }
    
    @Operation(summary = "8-2. 특정 유저 상세 조회", 
               description = "userId로 유저 상세 정보 조회 (이메일/전화번호는 마스킹)")
    @GetMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable Long userId) {
        String sql = """
            SELECT user_id, name, status, 
                   email_cipher, phone_cipher,
                   created_at, updated_at
            FROM USERS
            WHERE user_id = ?
            """;
        
        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql, userId);
        
        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        Map<String, Object> user = result.get(0);
        
        // 이메일/전화번호 마스킹
        String email = (String) user.get("email_cipher");
        String phone = (String) user.get("phone_cipher");
        
        user.put("email_masked", email != null ? maskEmail(email) : null);
        user.put("phone_masked", phone != null ? maskPhone(phone) : null);
        user.remove("email_cipher");
        user.remove("phone_cipher");
        
        // 유저의 알림 설정 조회
        String prefSql = """
            SELECT channel, enabled, quiet_start, quiet_end, priority
            FROM USER_NOTIFICATION_PREFS
            WHERE user_id = ?
            ORDER BY priority
            """;
        
        List<Map<String, Object>> prefs = jdbcTemplate.queryForList(prefSql, userId);
        user.put("notification_prefs", prefs);
        
        return ResponseEntity.ok(user);
    }
    
    @Operation(summary = "8-3. 알림 설정이 있는 유저 목록", 
               description = "금지시간 설정을 가진 유저 목록 조회")
    @GetMapping("/users/with-prefs")
    public ResponseEntity<Map<String, Object>> getUsersWithPrefs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        int offset = page * size;
        
        String sql = """
            SELECT DISTINCT u.user_id, u.name, u.status,
                   COUNT(unp.pref_id) as pref_count
            FROM USERS u
            INNER JOIN USER_NOTIFICATION_PREFS unp ON u.user_id = unp.user_id
            GROUP BY u.user_id, u.name, u.status
            ORDER BY pref_count DESC
            LIMIT ? OFFSET ?
            """;
        
        List<Map<String, Object>> users = jdbcTemplate.queryForList(sql, size, offset);
        
        return ResponseEntity.ok(Map.of(
            "page", page,
            "size", size,
            "users", users,
            "message", "알림 설정을 가진 유저 목록"
        ));
    }
    
    // ========================================
    // 청구서 조회
    // ========================================
    
    @Operation(summary = "8-4. 청구서 목록 조회", 
               description = "실제 DB의 청구서 목록 조회 (청구월 필터)")
    @GetMapping("/bills")
    public ResponseEntity<Map<String, Object>> getBills(
            @Parameter(description = "페이지 번호") 
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기") 
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "청구월 (YYYY-MM)") 
            @RequestParam(required = false) String billingMonth) {
        
        int offset = page * size;
        
        // 전체 개수 조회
        String countSql = "SELECT COUNT(*) FROM BILLS" + 
            (billingMonth != null ? " WHERE billing_month = ?" : "");
        
        Integer totalCount = billingMonth != null 
            ? jdbcTemplate.queryForObject(countSql, Integer.class, billingMonth)
            : jdbcTemplate.queryForObject(countSql, Integer.class);
        
        // 청구서 목록 조회
        String sql = """
            SELECT b.bill_id, b.user_id, b.billing_month,
                   b.settlement_date, b.bill_issue_date,
                   u.name as user_name,
                   COALESCE(SUM(bd.amount), 0) as total_amount
            FROM BILLS b
            LEFT JOIN USERS u ON b.user_id = u.user_id
            LEFT JOIN BILL_DETAILS bd ON b.bill_id = bd.bill_id
            """ + (billingMonth != null ? " WHERE b.billing_month = ? " : "") + """
            GROUP BY b.bill_id, b.user_id, b.billing_month, 
                     b.settlement_date, b.bill_issue_date, u.name
            ORDER BY b.created_at DESC
            LIMIT ? OFFSET ?
            """;
        
        List<Map<String, Object>> bills = billingMonth != null
            ? jdbcTemplate.queryForList(sql, billingMonth, size, offset)
            : jdbcTemplate.queryForList(sql, size, offset);
        
        return ResponseEntity.ok(Map.of(
            "totalCount", totalCount != null ? totalCount : 0,
            "page", page,
            "size", size,
            "totalPages", (totalCount != null ? totalCount : 0) / size + 1,
            "bills", bills
        ));
    }
    
    @Operation(summary = "8-5. 특정 청구서 상세 조회", 
               description = "billId로 청구서 상세 정보 조회 (청구 내역 포함)")
    @GetMapping("/bills/{billId}")
    public ResponseEntity<Map<String, Object>> getBill(@PathVariable Long billId) {
        // 청구서 기본 정보
        String billSql = """
            SELECT b.bill_id, b.user_id, b.billing_month,
                   b.settlement_date, b.bill_issue_date, b.created_at,
                   u.name as user_name, u.email_cipher, u.phone_cipher
            FROM BILLS b
            LEFT JOIN USERS u ON b.user_id = u.user_id
            WHERE b.bill_id = ?
            """;
        
        List<Map<String, Object>> billResult = jdbcTemplate.queryForList(billSql, billId);
        
        if (billResult.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        Map<String, Object> bill = billResult.get(0);
        
        // 이메일/전화번호 마스킹
        String email = (String) bill.get("email_cipher");
        String phone = (String) bill.get("phone_cipher");
        bill.put("email_masked", email != null ? maskEmail(email) : null);
        bill.put("phone_masked", phone != null ? maskPhone(phone) : null);
        bill.remove("email_cipher");
        bill.remove("phone_cipher");
        
        // 청구 상세 내역
        String detailSql = """
            SELECT detail_id, detail_type, charge_category, amount
            FROM BILL_DETAILS
            WHERE bill_id = ?
            ORDER BY detail_id
            """;
        
        List<Map<String, Object>> details = jdbcTemplate.queryForList(detailSql, billId);
        bill.put("details", details);
        
        // 총액 계산
        Integer totalAmount = details.stream()
            .mapToInt(d -> ((Number) d.get("amount")).intValue())
            .sum();
        bill.put("total_amount", totalAmount);
        
        // 알림 발송 이력
        String notificationSql = """
            SELECT notification_id, notification_type, notification_status,
                   sent_at, retry_count, error_message
            FROM NOTIFICATIONS
            WHERE bill_id = ?
            ORDER BY created_at DESC
            """;
        
        List<Map<String, Object>> notifications = jdbcTemplate.queryForList(notificationSql, billId);
        bill.put("notifications", notifications);
        
        return ResponseEntity.ok(bill);
    }
    
    @Operation(summary = "8-6. 특정 유저의 청구서 목록", 
               description = "userId로 해당 유저의 모든 청구서 조회")
    @GetMapping("/users/{userId}/bills")
    public ResponseEntity<Map<String, Object>> getUserBills(@PathVariable Long userId) {
        String sql = """
            SELECT b.bill_id, b.billing_month, b.settlement_date,
                   COALESCE(SUM(bd.amount), 0) as total_amount,
                   COUNT(n.notification_id) as notification_count
            FROM BILLS b
            LEFT JOIN BILL_DETAILS bd ON b.bill_id = bd.bill_id
            LEFT JOIN NOTIFICATIONS n ON b.bill_id = n.bill_id
            WHERE b.user_id = ?
            GROUP BY b.bill_id, b.billing_month, b.settlement_date
            ORDER BY b.billing_month DESC
            """;
        
        List<Map<String, Object>> bills = jdbcTemplate.queryForList(sql, userId);
        
        return ResponseEntity.ok(Map.of(
            "userId", userId,
            "billCount", bills.size(),
            "bills", bills
        ));
    }
    
    // ========================================
    // 통계
    // ========================================
    
    @Operation(summary = "8-7. 데이터 통계", 
               description = "전체 유저/청구서/알림 통계")
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        
        // 유저 통계
        stats.put("totalUsers", 
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM USERS", Integer.class));
        stats.put("activeUsers", 
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM USERS WHERE status = 'ACTIVE'", Integer.class));
        
        // 청구서 통계
        stats.put("totalBills", 
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM BILLS", Integer.class));
        
        String currentMonth = java.time.YearMonth.now().toString();
        stats.put("currentMonthBills", 
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM BILLS WHERE billing_month = ?", Integer.class, currentMonth));
        
        // 알림 통계
        stats.put("totalNotifications", 
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM NOTIFICATIONS", Integer.class));
        stats.put("sentNotifications", 
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM NOTIFICATIONS WHERE notification_status = 'SENT'", Integer.class));
        stats.put("failedNotifications", 
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM NOTIFICATIONS WHERE notification_status = 'FAILED'", Integer.class));
        
        // 알림 설정 통계
        stats.put("usersWithPrefs", 
            jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT user_id) FROM USER_NOTIFICATION_PREFS", Integer.class));
        
        return ResponseEntity.ok(stats);
    }
    
    @Operation(summary = "8-8. 최근 청구서 샘플 (테스트용)", 
               description = "최근 생성된 청구서 5개 샘플 조회 (빠른 테스트용)")
    @GetMapping("/bills/samples")
    public ResponseEntity<Map<String, Object>> getBillSamples() {
        String sql = """
            SELECT b.bill_id, b.user_id, b.billing_month,
                   u.name as user_name,
                   COALESCE(SUM(bd.amount), 0) as total_amount
            FROM BILLS b
            LEFT JOIN USERS u ON b.user_id = u.user_id
            LEFT JOIN BILL_DETAILS bd ON b.bill_id = bd.bill_id
            GROUP BY b.bill_id, b.user_id, b.billing_month, u.name
            ORDER BY b.created_at DESC
            LIMIT 5
            """;
        
        List<Map<String, Object>> samples = jdbcTemplate.queryForList(sql);
        
        return ResponseEntity.ok(Map.of(
            "message", "최근 생성된 청구서 샘플 5개",
            "samples", samples,
            "usage", "이 billId들로 /api/kafka-test/send-with-real-bill 테스트 가능"
        ));
    }
    
    // ========================================
    // Helper Methods
    // ========================================
    
    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***@***.***";
        String[] parts = email.split("@");
        String local = parts[0];
        String domain = parts[1];
        
        if (local.length() <= 2) {
            return "*".repeat(local.length()) + "@" + domain;
        }
        return local.charAt(0) + "*".repeat(local.length() - 2) + local.charAt(local.length() - 1) + "@" + domain;
    }
    
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "***-****-****";
        return phone.substring(0, 3) + "-****-" + phone.substring(phone.length() - 4);
    }
}