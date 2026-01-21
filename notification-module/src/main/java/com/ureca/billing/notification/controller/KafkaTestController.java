package com.ureca.billing.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.core.dto.BillingMessageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Kafka 통합 테스트 Controller (확장판)
 * 
 * 기존 테스트 + 실제 DB 데이터 기반 테스트
 * - 실제 billId로 발송
 * - 실제 userId로 발송
 * - 특정 청구월의 모든 청구서 발송
 */
@Tag(name = "2. Kafka 통합 테스트", description = "Kafka를 통한 멀티채널 발송 테스트 API")
@RestController
@RequestMapping("/api/kafka-test")
@RequiredArgsConstructor
@Slf4j
public class KafkaTestController {
    
    private static final String TOPIC = "billing-event";
    
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    
    // ========================================
    // 기존 API들 (단건 발송)
    // ========================================
    
    @Operation(summary = "2-1. EMAIL 발송 테스트", 
               description = "Kafka를 통해 EMAIL 알림 발송 (1초 delay, 1% 실패)")
    @PostMapping("/send/email")
    public ResponseEntity<Map<String, Object>> sendEmail(
            @Parameter(description = "청구서 ID") @RequestParam Long billId,
            @Parameter(description = "사용자 ID") @RequestParam Long userId,
            @Parameter(description = "수신 이메일") @RequestParam String email,
            @Parameter(description = "청구 금액") @RequestParam(defaultValue = "55000") Integer amount) {
        
        return sendMessage(billId, userId, "EMAIL", email, null, amount);
    }
    
    @Operation(summary = "2-2. SMS 발송 테스트", 
               description = "Kafka를 통해 SMS 알림 발송")
    @PostMapping("/send/sms")
    public ResponseEntity<Map<String, Object>> sendSms(
            @Parameter(description = "청구서 ID") @RequestParam Long billId,
            @Parameter(description = "사용자 ID") @RequestParam Long userId,
            @Parameter(description = "수신 전화번호") @RequestParam String phone,
            @Parameter(description = "청구 금액") @RequestParam(defaultValue = "55000") Integer amount) {
        
        return sendMessage(billId, userId, "SMS", null, phone, amount);
    }
    
    @Operation(summary = "2-3. PUSH 발송 테스트", 
               description = "Kafka를 통해 PUSH 알림 발송")
    @PostMapping("/send/push")
    public ResponseEntity<Map<String, Object>> sendPush(
            @Parameter(description = "청구서 ID") @RequestParam Long billId,
            @Parameter(description = "사용자 ID") @RequestParam Long userId,
            @Parameter(description = "청구 금액") @RequestParam(defaultValue = "55000") Integer amount) {
        
        return sendMessage(billId, userId, "PUSH", null, null, amount);
    }
    
    @Operation(summary = "2-4. 타입 지정 발송 테스트", 
               description = "알림 타입을 직접 지정하여 발송")
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendWithType(
            @RequestBody BillingMessageDto message) {
        
        try {
            String json = objectMapper.writeValueAsString(message);
            kafkaTemplate.send(TOPIC, json);
            
            log.info("📤 [Kafka 발송] billId={}, type={}", 
                message.getBillId(), message.getNotificationType());
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "action", "KAFKA_SENT",
                "message", "✅ Kafka로 메시지 발행 완료",
                "topic", TOPIC,
                "billId", message.getBillId(),
                "notificationType", message.getNotificationType()
            ));
            
        } catch (Exception e) {
            log.error("❌ Kafka 발송 실패", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "❌ 발송 실패: " + e.getMessage()
            ));
        }
    }
    
    // ========================================
    // 대량/멀티채널 테스트
    // ========================================
    
    @Operation(summary = "2-5. 대량 발송 테스트 (1% 실패 검증)", 
               description = "100건 발송하여 1% 실패율 및 DLT 동작 검증")
    @PostMapping("/send/bulk")
    public ResponseEntity<Map<String, Object>> sendBulk(
            @Parameter(description = "시작 billId") @RequestParam(defaultValue = "20001") Long startBillId,
            @Parameter(description = "발송 건수") @RequestParam(defaultValue = "100") Integer count,
            @Parameter(description = "알림 타입") @RequestParam(defaultValue = "EMAIL") String type) {
        
        log.info("📤 [대량 발송 시작] startBillId={}, count={}, type={}", startBillId, count, type);
        
        List<Long> sentBillIds = new ArrayList<>();
        
        try {
            for (int i = 0; i < count; i++) {
                Long billId = startBillId + i;
                Long userId = billId;
                
                BillingMessageDto message = BillingMessageDto.builder()
                    .billId(billId)
                    .userId(userId)
                    .notificationType(type)
                    .recipientEmail("test" + billId + "@example.com")
                    .recipientPhone("010-1234-" + String.format("%04d", i))
                    .totalAmount((long) (50000 + (i * 100)))
                    .billYearMonth("2026-01")
                    .billDate("2026-01-15")
                    .dueDate("2026-01-25")
                    .build();
                
                String json = objectMapper.writeValueAsString(message);
                kafkaTemplate.send(TOPIC, json);
                sentBillIds.add(billId);
            }
            
            log.info("✅ [대량 발송 완료] {}건 전송", count);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "action", "BULK_SENT",
                "message", String.format("✅ %d건 Kafka 발행 완료", count),
                "topic", TOPIC,
                "notificationType", type,
                "startBillId", startBillId,
                "endBillId", startBillId + count - 1,
                "expectedFailures", String.format("약 %d건 (1%%)", count / 100)
            ));
            
        } catch (Exception e) {
            log.error("❌ 대량 발송 실패", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "❌ 발송 실패: " + e.getMessage(),
                "sentCount", sentBillIds.size()
            ));
        }
    }
    
    @Operation(summary = "2-6. 멀티채널 동시 발송 테스트", 
               description = "같은 billId로 EMAIL, SMS, PUSH 3채널 동시 발송")
    @PostMapping("/send/multi-channel")
    public ResponseEntity<Map<String, Object>> sendMultiChannel(
            @Parameter(description = "청구서 ID") @RequestParam Long billId,
            @Parameter(description = "사용자 ID") @RequestParam Long userId,
            @Parameter(description = "이메일") @RequestParam String email,
            @Parameter(description = "전화번호") @RequestParam String phone,
            @Parameter(description = "청구 금액") @RequestParam(defaultValue = "55000") Integer amount) {
        
        log.info("📤 [멀티채널 발송] billId={}, userId={}", billId, userId);
        
        List<String> sentTypes = new ArrayList<>();
        
        try {
            // EMAIL
            sendMessageInternal(billId, userId, "EMAIL", email, null, amount);
            sentTypes.add("EMAIL");
            
            // SMS
            sendMessageInternal(billId, userId, "SMS", null, phone, amount);
            sentTypes.add("SMS");
            
            // PUSH
            sendMessageInternal(billId, userId, "PUSH", null, null, amount);
            sentTypes.add("PUSH");
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "action", "MULTI_CHANNEL_SENT",
                "message", "✅ 3채널 동시 발송 완료",
                "billId", billId,
                "sentTypes", sentTypes,
                "expectedRedisKeys", List.of(
                    "sent:msg:" + billId + ":EMAIL",
                    "sent:msg:" + billId + ":SMS",
                    "sent:msg:" + billId + ":PUSH"
                )
            ));
            
        } catch (Exception e) {
            log.error("❌ 멀티채널 발송 실패", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "❌ 발송 실패: " + e.getMessage(),
                "sentTypes", sentTypes
            ));
        }
    }
    
    @Operation(summary = "2-7. 중복 발송 테스트", 
               description = "같은 메시지 2번 발송하여 중복 방지 검증")
    @PostMapping("/send/duplicate-test")
    public ResponseEntity<Map<String, Object>> sendDuplicateTest(
            @Parameter(description = "청구서 ID") @RequestParam Long billId,
            @Parameter(description = "사용자 ID") @RequestParam Long userId,
            @Parameter(description = "알림 타입") @RequestParam(defaultValue = "EMAIL") String type) {
        
        log.info("📤 [중복 테스트] billId={}, type={} - 2회 발송", billId, type);
        
        try {
            // 1차 발송
            sendMessageInternal(billId, userId, type, 
                "test" + billId + "@example.com", "010-1234-5678", 55000);
            
            // 2차 발송 (중복)
            Thread.sleep(2000); // Consumer 처리 시간 대기
            sendMessageInternal(billId, userId, type, 
                "test" + billId + "@example.com", "010-1234-5678", 55000);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "action", "DUPLICATE_TEST_SENT",
                "message", "✅ 중복 테스트 메시지 2회 발송 완료",
                "billId", billId,
                "type", type,
                "expectedResult", "첫 번째: SENT, 두 번째: 중복 스킵",
                "checkLog", "Consumer 로그에서 '⚠️ 중복 메시지 스킵' 확인"
            ));
            
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "❌ 발송 실패: " + e.getMessage()
            ));
        }
    }
    
    // ========================================
    // 🆕 실제 DB 데이터 기반 발송
    // ========================================
    
    @Operation(summary = "2-8. 실제 청구서로 발송 테스트 ⭐", 
               description = "DB의 실제 billId로 알림 발송 (실제 유저 정보 자동 조회)")
    @PostMapping("/send-with-real-bill")
    public ResponseEntity<Map<String, Object>> sendWithRealBill(
            @Parameter(description = "실제 청구서 ID") @RequestParam Long billId,
            @Parameter(description = "알림 타입") @RequestParam(defaultValue = "EMAIL") String type) {
        
        log.info("📤 [실제 청구서 발송] billId={}, type={}", billId, type);
        
        try {
            // 1. 청구서 정보 조회
            String billSql = """
                SELECT b.bill_id, b.user_id, b.billing_month,
                       b.settlement_date, b.bill_issue_date,
                       u.name, u.email_cipher, u.phone_cipher,
                       COALESCE(SUM(bd.amount), 0) as total_amount
                FROM BILLS b
                LEFT JOIN USERS u ON b.user_id = u.user_id
                LEFT JOIN BILL_DETAILS bd ON b.bill_id = bd.bill_id
                WHERE b.bill_id = ?
                GROUP BY b.bill_id, b.user_id, b.billing_month,
                         b.settlement_date, b.bill_issue_date,
                         u.name, u.email_cipher, u.phone_cipher
                """;
            
            List<Map<String, Object>> result = jdbcTemplate.queryForList(billSql, billId);
            
            if (result.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "❌ 청구서를 찾을 수 없습니다. billId=" + billId
                ));
            }
            
            Map<String, Object> billData = result.get(0);
            
            // 2. BillingMessageDto 생성
            BillingMessageDto message = BillingMessageDto.builder()
                .billId(billId)
                .userId(((Number) billData.get("user_id")).longValue())
                .notificationType(type)
                .recipientEmail((String) billData.get("email_cipher"))
                .recipientPhone((String) billData.get("phone_cipher"))
                .totalAmount(((Number) billData.get("total_amount")).longValue())
                .billYearMonth((String) billData.get("billing_month"))
                .billDate(billData.get("bill_issue_date").toString())
                .dueDate(LocalDate.parse(billData.get("settlement_date").toString())
                        .plusDays(10).toString())
                .build();
            
            // 3. Kafka 발행
            String json = objectMapper.writeValueAsString(message);
            kafkaTemplate.send(TOPIC, json);
            
            log.info("✅ [실제 청구서 발송] billId={}, userId={}, amount={}원", 
                    billId, message.getUserId(), message.getTotalAmount());
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "action", "REAL_BILL_SENT",
                "message", "✅ 실제 청구서로 Kafka 발행 완료",
                "billInfo", Map.of(
                    "billId", billId,
                    "userId", message.getUserId(),
                    "userName", billData.get("name"),
                    "billingMonth", message.getBillYearMonth(),
                    "totalAmount", message.getTotalAmount(),
                    "notificationType", type
                ),
                "checkWith", List.of(
                    "GET /api/redis/check/" + billId + "?type=" + type,
                    "GET /api/retry/status-summary"
                )
            ));
            
        } catch (Exception e) {
            log.error("❌ 실제 청구서 발송 실패", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "❌ 발송 실패: " + e.getMessage()
            ));
        }
    }
    
    @Operation(summary = "2-9. 실제 유저의 모든 청구서 발송 ⭐", 
               description = "특정 userId의 모든 청구서에 대해 알림 발송")
    @PostMapping("/send-user-bills")
    public ResponseEntity<Map<String, Object>> sendUserBills(
            @Parameter(description = "사용자 ID") @RequestParam Long userId,
            @Parameter(description = "알림 타입") @RequestParam(defaultValue = "EMAIL") String type) {
        
        log.info("📤 [유저 전체 청구서 발송] userId={}, type={}", userId, type);
        
        try {
            // 1. 유저의 청구서 목록 조회
            String sql = """
                SELECT b.bill_id, b.billing_month,
                       COALESCE(SUM(bd.amount), 0) as total_amount
                FROM BILLS b
                LEFT JOIN BILL_DETAILS bd ON b.bill_id = bd.bill_id
                WHERE b.user_id = ?
                GROUP BY b.bill_id, b.billing_month
                ORDER BY b.billing_month DESC
                """;
            
            List<Map<String, Object>> bills = jdbcTemplate.queryForList(sql, userId);
            
            if (bills.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "❌ 청구서를 찾을 수 없습니다. userId=" + userId
                ));
            }
            
            // 2. 유저 정보 조회
            String userSql = "SELECT name, email_cipher, phone_cipher FROM USERS WHERE user_id = ?";
            Map<String, Object> userData = jdbcTemplate.queryForMap(userSql, userId);
            
            // 3. 각 청구서에 대해 발송
            List<Long> sentBillIds = new ArrayList<>();
            for (Map<String, Object> bill : bills) {
                Long billId = ((Number) bill.get("bill_id")).longValue();
                
                BillingMessageDto message = BillingMessageDto.builder()
                    .billId(billId)
                    .userId(userId)
                    .notificationType(type)
                    .recipientEmail((String) userData.get("email_cipher"))
                    .recipientPhone((String) userData.get("phone_cipher"))
                    .totalAmount(((Number) bill.get("total_amount")).longValue())
                    .billYearMonth((String) bill.get("billing_month"))
                    .build();
                
                String json = objectMapper.writeValueAsString(message);
                kafkaTemplate.send(TOPIC, json);
                sentBillIds.add(billId);
            }
            
            log.info("✅ [유저 전체 청구서 발송] userId={}, 발송건수={}", userId, sentBillIds.size());
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "action", "USER_BILLS_SENT",
                "message", String.format("✅ %d건의 청구서 발송 완료", sentBillIds.size()),
                "userInfo", Map.of(
                    "userId", userId,
                    "userName", userData.get("name"),
                    "billCount", sentBillIds.size()
                ),
                "sentBillIds", sentBillIds
            ));
            
        } catch (Exception e) {
            log.error("❌ 유저 청구서 발송 실패", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "❌ 발송 실패: " + e.getMessage()
            ));
        }
    }
    
    @Operation(summary = "2-10. 특정 청구월의 모든 청구서 발송 ⭐", 
               description = "특정 월의 모든 청구서에 대해 알림 발송 (배치 시뮬레이션)")
    @PostMapping("/send-month-bills")
    public ResponseEntity<Map<String, Object>> sendMonthBills(
            @Parameter(description = "청구월 (YYYY-MM)") @RequestParam String billingMonth,
            @Parameter(description = "알림 타입") @RequestParam(defaultValue = "EMAIL") String type,
            @Parameter(description = "최대 발송 건수") @RequestParam(defaultValue = "100") int limit) {
        
        log.info("📤 [월별 청구서 발송] billingMonth={}, type={}, limit={}", billingMonth, type, limit);
        
        try {
            // 1. 해당 월의 청구서 조회
            String sql = """
                SELECT b.bill_id, b.user_id, b.billing_month,
                       u.name, u.email_cipher, u.phone_cipher,
                       COALESCE(SUM(bd.amount), 0) as total_amount
                FROM BILLS b
                LEFT JOIN USERS u ON b.user_id = u.user_id
                LEFT JOIN BILL_DETAILS bd ON b.bill_id = bd.bill_id
                WHERE b.billing_month = ?
                GROUP BY b.bill_id, b.user_id, b.billing_month,
                         u.name, u.email_cipher, u.phone_cipher
                LIMIT ?
                """;
            
            List<Map<String, Object>> bills = jdbcTemplate.queryForList(sql, billingMonth, limit);
            
            if (bills.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "❌ 해당 월의 청구서가 없습니다. billingMonth=" + billingMonth
                ));
            }
            
            // 2. 각 청구서에 대해 발송
            List<Long> sentBillIds = new ArrayList<>();
            for (Map<String, Object> bill : bills) {
                BillingMessageDto message = BillingMessageDto.builder()
                    .billId(((Number) bill.get("bill_id")).longValue())
                    .userId(((Number) bill.get("user_id")).longValue())
                    .notificationType(type)
                    .recipientEmail((String) bill.get("email_cipher"))
                    .recipientPhone((String) bill.get("phone_cipher"))
                    .totalAmount(((Number) bill.get("total_amount")).longValue())
                    .billYearMonth(billingMonth)
                    .build();
                
                String json = objectMapper.writeValueAsString(message);
                kafkaTemplate.send(TOPIC, json);
                sentBillIds.add(message.getBillId());
            }
            
            log.info("✅ [월별 청구서 발송] billingMonth={}, 발송건수={}", billingMonth, sentBillIds.size());
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "action", "MONTH_BILLS_SENT",
                "message", String.format("✅ %s월 청구서 %d건 발송 완료", billingMonth, sentBillIds.size()),
                "billingMonth", billingMonth,
                "sentCount", sentBillIds.size(),
                "sentBillIds", sentBillIds,
                "note", "배치 정산 시뮬레이션 - 실제 배치에서는 이 방식으로 발송됨"
            ));
            
        } catch (Exception e) {
            log.error("❌ 월별 청구서 발송 실패", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "❌ 발송 실패: " + e.getMessage()
            ));
        }
    }
    
    // ========================================
    // Private Helper Methods
    // ========================================
    
    private ResponseEntity<Map<String, Object>> sendMessage(
            Long billId, Long userId, String type, 
            String email, String phone, Integer amount) {
        
        try {
            sendMessageInternal(billId, userId, type, email, phone, amount);
            
            log.info("📤 [Kafka 발송] billId={}, type={}", billId, type);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("action", "KAFKA_SENT");
            response.put("message", "✅ Kafka로 메시지 발행 완료");
            response.put("topic", TOPIC);
            response.put("billId", billId);
            response.put("userId", userId);
            response.put("notificationType", type);
            response.put("amount", amount);
            
            if (email != null) response.put("email", email);
            if (phone != null) response.put("phone", phone);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("❌ Kafka 발송 실패", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "❌ 발송 실패: " + e.getMessage()
            ));
        }
    }
    
    private void sendMessageInternal(Long billId, Long userId, String type,
                                      String email, String phone, Integer amount) throws Exception {
        
        BillingMessageDto message = BillingMessageDto.builder()
            .billId(billId)
            .userId(userId)
            .notificationType(type)
            .recipientEmail(email != null ? email : "test" + billId + "@example.com")
            .recipientPhone(phone != null ? phone : "010-1234-5678")
            .totalAmount(amount.longValue())
            .billYearMonth("2026-01")
            .billDate("2026-01-15")
            .dueDate("2026-01-25")
            .build();
        
        String json = objectMapper.writeValueAsString(message);
        kafkaTemplate.send(TOPIC, json);
    }
}