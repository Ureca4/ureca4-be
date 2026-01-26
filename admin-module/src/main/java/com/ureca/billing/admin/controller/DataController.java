package com.ureca.billing.admin.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Tag(name = "Admin - 데이터 조회", description = "유저 및 청구서 데이터 조회 API")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
public class DataController {

    private final JdbcTemplate jdbcTemplate;

    // ========================================
    // 유저 조회
    // ========================================

    @Operation(summary = "유저 목록 조회", 
               description = "페이징, 상태 필터, 검색 지원")
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> getUsers(
            @Parameter(description = "페이지 번호 (0부터 시작)") 
            @RequestParam(name = "page", defaultValue = "0") int page,
            @Parameter(description = "페이지 크기") 
            @RequestParam(name = "size", defaultValue = "20") int size,
            @Parameter(description = "상태 필터 (ACTIVE, SUSPENDED, TERMINATED)") 
            @RequestParam(name = "status", required = false) String status,
            @Parameter(description = "검색어 (user_id 또는 name으로 검색)") 
            @RequestParam(name = "search", required = false) String search) {
        
        int offset = page * size;
        
        // WHERE 조건 구성
        List<String> whereConditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        
        if (status != null && !status.isEmpty()) {
            whereConditions.add("status = ?");
            params.add(status);
        }
        
        if (search != null && !search.trim().isEmpty()) {
            String searchPattern = "%" + search.trim() + "%";
            whereConditions.add("(CAST(user_id AS CHAR) LIKE ? OR name LIKE ?)");
            params.add(searchPattern);
            params.add(searchPattern);
        }
        
        String whereClause = whereConditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", whereConditions);
        
        // 전체 개수 조회
        String countSql = "SELECT COUNT(*) FROM USERS" + whereClause;
        Integer totalCount = params.isEmpty()
            ? jdbcTemplate.queryForObject(countSql, Integer.class)
            : jdbcTemplate.queryForObject(countSql, Integer.class, params.toArray());
        
        // 유저 목록 조회
        String sql = """
            SELECT user_id, name, status, created_at
            FROM USERS
            """ + whereClause + """
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """;
        
        params.add(size);
        params.add(offset);
        
        List<Map<String, Object>> users = jdbcTemplate.queryForList(sql, params.toArray());
        
        return ResponseEntity.ok(Map.of(
            "totalCount", totalCount != null ? totalCount : 0,
            "page", page,
            "size", size,
            "totalPages", (totalCount != null ? totalCount : 0) / size + 1,
            "users", users
        ));
    }

    @Operation(summary = "유저 상세 조회", 
               description = "userId로 유저 상세 정보 조회 (이메일/전화번호 마스킹)")
    @GetMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable(name = "userId") Long userId) {
        String sql = """
            SELECT user_id, name, status, birth_date,
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
            SELECT channel, enabled, quiet_start, quiet_end, priority,
                   preferred_day, preferred_hour, preferred_minute
            FROM USER_NOTIFICATION_PREFS
            WHERE user_id = ?
            ORDER BY priority
            """;
        
        List<Map<String, Object>> prefs = jdbcTemplate.queryForList(prefSql, userId);
        user.put("notification_prefs", prefs);
        
        // 현재 요금제 정보 조회
        String planSql = """
            SELECT up.user_plan_id, up.plan_id, up.start_date, up.end_date, up.status,
                   p.plan_name, p.plan_category, p.plan_type, p.monthly_fee,
                   p.data_limit, p.voice_limit
            FROM USER_PLANS up
            LEFT JOIN PLANS p ON up.plan_id = p.plan_id
            WHERE up.user_id = ? AND up.status = 'ACTIVE'
            ORDER BY up.start_date DESC
            LIMIT 1
            """;
        
        List<Map<String, Object>> plans = jdbcTemplate.queryForList(planSql, userId);
        user.put("current_plan", plans.isEmpty() ? null : plans.get(0));
        
        // 부가서비스 목록 조회
        String addonSql = """
            SELECT ua.user_addon_id, ua.addon_id, ua.start_date, ua.end_date, ua.status,
                   a.addon_name, a.addon_category, a.monthly_fee
            FROM USER_ADDONS ua
            LEFT JOIN ADDONS a ON ua.addon_id = a.addon_id
            WHERE ua.user_id = ? AND ua.status = 'ACTIVE'
            ORDER BY ua.start_date DESC
            """;
        
        List<Map<String, Object>> addons = jdbcTemplate.queryForList(addonSql, userId);
        user.put("addons", addons);
        
        // 기기 할부 정보 조회
        String deviceSql = """
            SELECT installment_id, device_name, original_price, installment_principal,
                   monthly_fee, total_months, remaining_months, status, created_at
            FROM DEVICE_INSTALLMENTS
            WHERE user_id = ? AND status = 'ONGOING'
            ORDER BY created_at DESC
            """;
        
        List<Map<String, Object>> devices = jdbcTemplate.queryForList(deviceSql, userId);
        user.put("devices", devices);
        
        // 최근 소액결제 내역 조회 (최근 10건)
        String microPaymentSql = """
            SELECT payment_id, amount, merchant_name, payment_type, payment_date, created_at
            FROM MICRO_PAYMENTS
            WHERE user_id = ?
            ORDER BY payment_date DESC
            LIMIT 10
            """;
        
        List<Map<String, Object>> microPayments = jdbcTemplate.queryForList(microPaymentSql, userId);
        user.put("recent_micro_payments", microPayments);
        
        // 최근 청구서 목록 조회 (최근 5건)
        String billSql = """
            SELECT b.bill_id, b.billing_month, b.settlement_date, b.bill_issue_date, b.created_at,
                   COALESCE((
                       SELECT SUM(bd.amount) 
                       FROM BILL_DETAILS bd 
                       WHERE bd.bill_id = b.bill_id
                   ), 0) as total_amount
            FROM BILLS b
            WHERE b.user_id = ?
            ORDER BY b.created_at DESC
            LIMIT 5
            """;
        
        List<Map<String, Object>> bills = jdbcTemplate.queryForList(billSql, userId);
        user.put("recent_bills", bills);
        
        return ResponseEntity.ok(user);
    }

    // ========================================
    // 청구서 조회
    // ========================================

    @Operation(summary = "청구서 목록 조회", 
               description = "페이징 및 청구월 필터 지원")
    @GetMapping("/bills")
    public ResponseEntity<Map<String, Object>> getBills(
            @Parameter(description = "페이지 번호") 
            @RequestParam(name = "page", defaultValue = "0") int page,
            @Parameter(description = "페이지 크기") 
            @RequestParam(name = "size", defaultValue = "20") int size,
            @Parameter(description = "청구월 (YYYY-MM)") 
            @RequestParam(name = "billingMonth", required = false) String billingMonth) {
        
        int offset = page * size;
        
        // 전체 개수 조회
        String countSql = "SELECT COUNT(*) FROM BILLS" + 
            (billingMonth != null ? " WHERE billing_month = ?" : "");
        
        Integer totalCount = billingMonth != null 
            ? jdbcTemplate.queryForObject(countSql, Integer.class, billingMonth)
            : jdbcTemplate.queryForObject(countSql, Integer.class);
        
        // 청구서 목록 조회 (최적화: 서브쿼리로 total_amount 계산)
        String sql = """
            SELECT b.bill_id, b.user_id, b.billing_month,
                   b.settlement_date, b.bill_issue_date,
                   u.name as user_name,
                   COALESCE((
                       SELECT SUM(bd.amount) 
                       FROM BILL_DETAILS bd 
                       WHERE bd.bill_id = b.bill_id
                   ), 0) as total_amount
            FROM BILLS b
            LEFT JOIN USERS u ON b.user_id = u.user_id
            """ + (billingMonth != null ? " WHERE b.billing_month = ? " : "") + """
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

    @Operation(summary = "청구서 상세 조회", 
               description = "billId로 청구서 상세 정보 조회 (청구 내역, 알림 이력 포함)")
    @GetMapping("/bills/{billId}")
    public ResponseEntity<Map<String, Object>> getBill(@PathVariable(name = "billId") Long billId) {
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
        
        // 청구 상세 내역 (관련 정보 포함)
        // charge_category별로 관련 테이블과 조인하여 상세 정보 조회
        String billingMonth = (String) bill.get("billing_month");
        String detailSql = """
            SELECT 
                bd.detail_id,
                bd.bill_id,
                bd.detail_type,
                bd.charge_category,
                bd.amount,
                bd.related_user_id,
                bd.created_at,
                -- ADDON_FEE 관련 정보
                CASE WHEN bd.charge_category = 'ADDON_FEE' THEN a.addon_name ELSE NULL END as addon_name,
                CASE WHEN bd.charge_category = 'ADDON_FEE' THEN a.addon_category ELSE NULL END as addon_category,
                -- DEVICE_FEE 관련 정보
                CASE WHEN bd.charge_category = 'DEVICE_FEE' THEN di.device_name ELSE NULL END as device_name,
                CASE WHEN bd.charge_category = 'DEVICE_FEE' THEN di.monthly_fee ELSE NULL END as device_monthly_fee,
                CASE WHEN bd.charge_category = 'DEVICE_FEE' THEN di.remaining_months ELSE NULL END as device_remaining_months,
                -- MICRO_PAYMENT 관련 정보
                CASE WHEN bd.charge_category = 'MICRO_PAYMENT' THEN mp.merchant_name ELSE NULL END as merchant_name,
                CASE WHEN bd.charge_category = 'MICRO_PAYMENT' THEN mp.payment_type ELSE NULL END as payment_type,
                CASE WHEN bd.charge_category = 'MICRO_PAYMENT' THEN mp.payment_date ELSE NULL END as payment_date,
                -- BASE_FEE 관련 정보
                CASE WHEN bd.charge_category = 'BASE_FEE' THEN p.plan_name ELSE NULL END as plan_name,
                CASE WHEN bd.charge_category = 'BASE_FEE' THEN p.plan_category ELSE NULL END as plan_category,
                CASE WHEN bd.charge_category = 'BASE_FEE' THEN p.plan_type ELSE NULL END as plan_type
            FROM BILL_DETAILS bd
            LEFT JOIN USER_ADDONS ua ON bd.charge_category = 'ADDON_FEE' 
                AND bd.related_user_id = ua.user_id 
                AND ? >= DATE_FORMAT(ua.start_date, '%Y-%m')
                AND (ua.end_date IS NULL OR ? <= DATE_FORMAT(ua.end_date, '%Y-%m'))
                AND ua.status = 'ACTIVE'
            LEFT JOIN ADDONS a ON ua.addon_id = a.addon_id
            LEFT JOIN DEVICE_INSTALLMENTS di ON bd.charge_category = 'DEVICE_FEE' 
                AND bd.related_user_id = di.user_id 
                AND di.status = 'ONGOING'
            LEFT JOIN MICRO_PAYMENTS mp ON bd.charge_category = 'MICRO_PAYMENT' 
                AND bd.related_user_id = mp.user_id 
                AND DATE_FORMAT(mp.payment_date, '%Y-%m') = ?
                AND mp.amount = bd.amount
            LEFT JOIN USER_PLANS up ON bd.charge_category = 'BASE_FEE' 
                AND bd.related_user_id = up.user_id 
                AND ? >= DATE_FORMAT(up.start_date, '%Y-%m')
                AND (up.end_date IS NULL OR ? <= DATE_FORMAT(up.end_date, '%Y-%m'))
                AND up.status = 'ACTIVE'
            LEFT JOIN PLANS p ON up.plan_id = p.plan_id
            WHERE bd.bill_id = ?
            ORDER BY bd.detail_id
            """;
        
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
            detailSql, 
            billingMonth, billingMonth,  // ADDON_FEE용
            billingMonth,  // MICRO_PAYMENT용
            billingMonth, billingMonth,  // BASE_FEE용
            billId
        );
        bill.put("details", details);
        
        // 총액 계산
        Integer totalAmount = details.stream()
            .mapToInt(d -> ((Number) d.get("amount")).intValue())
            .sum();
        bill.put("total_amount", totalAmount);
        
        // 알림 발송 이력
        String notificationSql = """
            SELECT notification_id, notification_type, notification_status,
                   sent_at, retry_count, error_message, created_at
            FROM NOTIFICATIONS
            WHERE bill_id = ?
            ORDER BY created_at DESC
            """;
        
        List<Map<String, Object>> notifications = jdbcTemplate.queryForList(notificationSql, billId);
        bill.put("notifications", notifications);
        
        return ResponseEntity.ok(bill);
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
