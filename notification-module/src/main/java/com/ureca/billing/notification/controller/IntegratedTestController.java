package com.ureca.billing.notification.controller;

import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.consumer.handler.DuplicateCheckHandler;
import com.ureca.billing.notification.domain.dto.QuietTimeCheckResult;
import com.ureca.billing.notification.service.EmailService;
import com.ureca.billing.notification.service.MessagePolicyService;
import com.ureca.billing.notification.service.UserQuietTimeService;
import com.ureca.billing.notification.service.WaitingQueueService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 통합 테스트 Controller
 * 
 * Kafka를 우회한 직접 발송 테스트용 (즉시 결과 확인)
 * - 멀티채널 직접 발송 (EMAIL, SMS, PUSH)
 * - 시스템/사용자별 금지시간 테스트
 * - 🆕 시간 시뮬레이션 발송 테스트
 * - 중복 발송 방지 검증
 * 
 * ⚠️ 개발/디버깅용. 프로덕션에서는 KafkaTestController 사용 권장
 */
@Tag(name = "1. 직접 발송 테스트 (개발용)", description = "Kafka 우회 직접 발송 - 즉시 결과 확인용")
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Slf4j
public class IntegratedTestController {
    
    private final MessagePolicyService policyService;
    private final UserQuietTimeService quietTimeService;
    private final WaitingQueueService queueService;
    private final EmailService emailService;
    private final DuplicateCheckHandler duplicateCheckHandler;
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    // ========================================
    // EMAIL 직접 발송
    // ========================================
    
    @Operation(summary = "1-1. EMAIL 즉시 발송", 
               description = "⚠️ Kafka 우회 직접 발송. 시스템 금지시간(22:00~08:00) + 중복 체크 적용")
    @PostMapping("/email/send")
    public ResponseEntity<Map<String, Object>> sendEmail(@RequestBody BillingMessageDto message) {
        return sendDirect(message, "EMAIL");
    }
    
    @Operation(summary = "1-2. EMAIL 사용자별 금지시간 적용 발송", 
               description = "사용자 설정 금지시간 + 시스템 정책 + 중복 체크 통합")
    @PostMapping("/email/send-with-user-pref")
    public ResponseEntity<Map<String, Object>> sendEmailWithUserPref(@RequestBody BillingMessageDto message) {
        return sendWithUserPref(message, "EMAIL");
    }
    
    @Operation(summary = "1-3. 🆕 EMAIL 시간 지정 발송 (시스템 정책)", 
               description = "특정 시간을 지정하여 시스템 금지시간 기준으로 발송/대기열 저장 테스트")
    @PostMapping("/email/send-at-time")
    public ResponseEntity<Map<String, Object>> sendEmailAtTime(
            @RequestBody BillingMessageDto message,
            @Parameter(description = "시뮬레이션 시간 (HH:mm)") @RequestParam String simulatedTime) {
        
        LocalTime testTime = LocalTime.parse(simulatedTime);
        return sendDirectAtTime(message, "EMAIL", testTime);
    }
    
    @Operation(summary = "1-4. 🆕 EMAIL 시간 지정 발송 (사용자별)", 
               description = "특정 시간을 지정하여 사용자별 금지시간 기준으로 발송/대기열 저장 테스트")
    @PostMapping("/email/send-with-user-pref-at-time")
    public ResponseEntity<Map<String, Object>> sendEmailWithUserPrefAtTime(
            @RequestBody BillingMessageDto message,
            @Parameter(description = "시뮬레이션 시간 (HH:mm)") @RequestParam String simulatedTime) {
        
        LocalTime testTime = LocalTime.parse(simulatedTime);
        return sendWithUserPrefAtTime(message, "EMAIL", testTime);
    }
    
    // ========================================
    // SMS 직접 발송
    // ========================================
    
    @Operation(summary = "1-5. SMS 즉시 발송", 
               description = "⚠️ Kafka 우회 SMS 직접 발송 (시뮬레이션)")
    @PostMapping("/sms/send")
    public ResponseEntity<Map<String, Object>> sendSms(@RequestBody BillingMessageDto message) {
        return sendDirect(message, "SMS");
    }
    
    @Operation(summary = "1-6. SMS 사용자별 금지시간 적용 발송", 
               description = "사용자 설정 금지시간 + 시스템 정책 + 중복 체크 통합")
    @PostMapping("/sms/send-with-user-pref")
    public ResponseEntity<Map<String, Object>> sendSmsWithUserPref(@RequestBody BillingMessageDto message) {
        return sendWithUserPref(message, "SMS");
    }
    
    @Operation(summary = "1-7. 🆕 SMS 시간 지정 발송 (시스템 정책)", 
               description = "특정 시간을 지정하여 시스템 금지시간 기준으로 발송/대기열 저장 테스트")
    @PostMapping("/sms/send-at-time")
    public ResponseEntity<Map<String, Object>> sendSmsAtTime(
            @RequestBody BillingMessageDto message,
            @Parameter(description = "시뮬레이션 시간 (HH:mm)") @RequestParam String simulatedTime) {
        
        LocalTime testTime = LocalTime.parse(simulatedTime);
        return sendDirectAtTime(message, "SMS", testTime);
    }
    
    @Operation(summary = "1-8. 🆕 SMS 시간 지정 발송 (사용자별)", 
               description = "특정 시간을 지정하여 사용자별 금지시간 기준으로 발송/대기열 저장 테스트")
    @PostMapping("/sms/send-with-user-pref-at-time")
    public ResponseEntity<Map<String, Object>> sendSmsWithUserPrefAtTime(
            @RequestBody BillingMessageDto message,
            @Parameter(description = "시뮬레이션 시간 (HH:mm)") @RequestParam String simulatedTime) {
        
        LocalTime testTime = LocalTime.parse(simulatedTime);
        return sendWithUserPrefAtTime(message, "SMS", testTime);
    }
    
    // ========================================
    // PUSH 직접 발송
    // ========================================
    
    @Operation(summary = "1-9. PUSH 즉시 발송", 
               description = "⚠️ Kafka 우회 PUSH 직접 발송 (시뮬레이션)")
    @PostMapping("/push/send")
    public ResponseEntity<Map<String, Object>> sendPush(@RequestBody BillingMessageDto message) {
        return sendDirect(message, "PUSH");
    }
    
    @Operation(summary = "1-10. PUSH 사용자별 금지시간 적용 발송", 
               description = "사용자 설정 금지시간 + 시스템 정책 + 중복 체크 통합")
    @PostMapping("/push/send-with-user-pref")
    public ResponseEntity<Map<String, Object>> sendPushWithUserPref(@RequestBody BillingMessageDto message) {
        return sendWithUserPref(message, "PUSH");
    }
    
    @Operation(summary = "1-11. 🆕 PUSH 시간 지정 발송 (시스템 정책)", 
               description = "특정 시간을 지정하여 시스템 금지시간 기준으로 발송/대기열 저장 테스트")
    @PostMapping("/push/send-at-time")
    public ResponseEntity<Map<String, Object>> sendPushAtTime(
            @RequestBody BillingMessageDto message,
            @Parameter(description = "시뮬레이션 시간 (HH:mm)") @RequestParam String simulatedTime) {
        
        LocalTime testTime = LocalTime.parse(simulatedTime);
        return sendDirectAtTime(message, "PUSH", testTime);
    }
    
    @Operation(summary = "1-12. 🆕 PUSH 시간 지정 발송 (사용자별)", 
               description = "특정 시간을 지정하여 사용자별 금지시간 기준으로 발송/대기열 저장 테스트")
    @PostMapping("/push/send-with-user-pref-at-time")
    public ResponseEntity<Map<String, Object>> sendPushWithUserPrefAtTime(
            @RequestBody BillingMessageDto message,
            @Parameter(description = "시뮬레이션 시간 (HH:mm)") @RequestParam String simulatedTime) {
        
        LocalTime testTime = LocalTime.parse(simulatedTime);
        return sendWithUserPrefAtTime(message, "PUSH", testTime);
    }
    
    // ========================================
    // 금지시간 체크 (발송 안함)
    // ========================================
    
    @Operation(summary = "1-13. 시스템 금지시간 체크", 
               description = "지정한 시간이 시스템 금지시간인지만 확인 (실제 발송 X)")
    @GetMapping("/check-time")
    public ResponseEntity<Map<String, Object>> checkTime(
            @Parameter(description = "확인할 시간 (HH:mm)") @RequestParam String time) {
        
        LocalTime checkTime = LocalTime.parse(time);
        LocalTime actualTime = LocalTime.now();
        boolean isBlock = policyService.isBlockTime(checkTime);
        
        return ResponseEntity.ok(Map.of(
            "checkTime", time,
            "actualTime", actualTime.toString(),
            "isBlockTime", isBlock,
            "blockPeriod", "22:00 ~ 08:00",
            "result", isBlock ? "⛔ 금지 시간" : "✅ 발송 가능 시간"
        ));
    }
    
    @Operation(summary = "1-14. 사용자별 금지시간 체크", 
               description = "특정 시간에 특정 사용자의 금지시간 여부 확인")
    @GetMapping("/user/{userId}/check-time")
    public ResponseEntity<QuietTimeCheckResult> checkUserQuietTime(
            @PathVariable Long userId,
            @Parameter(description = "채널 (EMAIL, SMS, PUSH)") 
            @RequestParam(defaultValue = "EMAIL") String channel,
            @Parameter(description = "확인할 시간 (HH:mm)") 
            @RequestParam String time) {
        
        LocalTime checkTime = LocalTime.parse(time);
        QuietTimeCheckResult result = quietTimeService.checkQuietTime(userId, channel, checkTime);
        return ResponseEntity.ok(result);
    }
    
    // ========================================
    // Private Helper Methods
    // ========================================
    
    /**
     * 직접 발송 (시스템 정책만 적용, 현재 시간)
     */
    private ResponseEntity<Map<String, Object>> sendDirect(BillingMessageDto message, String type) {
        LocalTime now = LocalTime.now();
        log.info("🧪 [직접 발송-{}] billId={}, currentTime={}", type, message.getBillId(), now);
        
        Map<String, Object> response = new HashMap<>();
        response.put("billId", message.getBillId());
        response.put("userId", message.getUserId());
        response.put("channel", type);
        response.put("currentTime", now.toString());
        response.put("testMode", "DIRECT_SEND (Kafka 우회)");
        
        // 1. 중복 체크
        if (duplicateCheckHandler.isDuplicate(message.getBillId(), type)) {
            log.warn("⚠️ [직접 발송-{}] 중복 차단. billId={}", type, message.getBillId());
            response.put("result", "DUPLICATE_BLOCKED");
            response.put("message", String.format("⚠️ 이미 발송된 %s입니다 (Redis 키 존재)", type));
            response.put("redisKey", "sent:msg:" + message.getBillId() + ":" + type);
            return ResponseEntity.ok(response);
        }
        
        // 2. 금지 시간 체크
        if (policyService.isBlockTime()) {
            log.info("⏰ [직접 발송-{}] 금지 시간 - 대기열 저장. billId={}", type, message.getBillId());
            queueService.addToQueue(message);
            response.put("result", "QUEUED");
            response.put("message", "⏰ 금지 시간입니다. 대기열에 저장되었습니다.");
            response.put("blockPeriod", "22:00 ~ 08:00");
            response.put("queueSize", queueService.getQueueSize());
            return ResponseEntity.ok(response);
        }
        
        // 3. 발송
        return executeDelivery(message, type, response);
    }
    
    /**
     * 🆕 시간 지정 직접 발송 (시스템 정책, 특정 시간)
     */
    private ResponseEntity<Map<String, Object>> sendDirectAtTime(
            BillingMessageDto message, String type, LocalTime testTime) {
        
        LocalTime actualTime = LocalTime.now();
        log.info("🧪 [시간 지정 발송-{}] billId={}, testTime={}, actualTime={}", 
                type, message.getBillId(), testTime, actualTime);
        
        Map<String, Object> response = new HashMap<>();
        response.put("billId", message.getBillId());
        response.put("userId", message.getUserId());
        response.put("channel", type);
        response.put("simulatedTime", testTime.toString());
        response.put("actualTime", actualTime.toString());
        response.put("testMode", "TIME_SIMULATION (시간 지정)");
        
        // 1. 중복 체크
        if (duplicateCheckHandler.isDuplicate(message.getBillId(), type)) {
            log.warn("⚠️ [시간 지정 발송-{}] 중복 차단. billId={}", type, message.getBillId());
            response.put("result", "DUPLICATE_BLOCKED");
            response.put("message", String.format("⚠️ 이미 발송된 %s입니다 (Redis 키 존재)", type));
            response.put("redisKey", "sent:msg:" + message.getBillId() + ":" + type);
            return ResponseEntity.ok(response);
        }
        
        // 2. 지정된 시간 기준으로 금지 시간 체크
        if (policyService.isBlockTime(testTime)) {
            log.info("⏰ [시간 지정 발송-{}] 금지 시간 - 대기열 저장. billId={}, testTime={}", 
                    type, message.getBillId(), testTime);
            queueService.addToQueue(message);
            response.put("result", "QUEUED");
            response.put("message", String.format("⏰ %s는 금지 시간입니다. 대기열에 저장되었습니다.", testTime));
            response.put("blockPeriod", "22:00 ~ 08:00");
            response.put("queueSize", queueService.getQueueSize());
            return ResponseEntity.ok(response);
        }
        
        // 3. 발송
        return executeDelivery(message, type, response);
    }
    
    /**
     * 사용자별 금지시간 적용 발송 (현재 시간)
     */
    private ResponseEntity<Map<String, Object>> sendWithUserPref(BillingMessageDto message, String type) {
        LocalTime now = LocalTime.now();
        log.info("🧪 [사용자별 금지시간-{}] userId={}, billId={}, time={}", 
                type, message.getUserId(), message.getBillId(), now);
        
        Map<String, Object> response = new HashMap<>();
        response.put("billId", message.getBillId());
        response.put("userId", message.getUserId());
        response.put("channel", type);
        response.put("currentTime", now.toString());
        
        // 1. 중복 체크
        if (duplicateCheckHandler.isDuplicate(message.getBillId(), type)) {
            log.warn("⚠️ [사용자별-{}] 중복 차단. billId={}", type, message.getBillId());
            response.put("result", "DUPLICATE_BLOCKED");
            response.put("message", String.format("⚠️ 이미 발송된 %s입니다", type));
            return ResponseEntity.ok(response);
        }
        
        // 2. 사용자별 금지시간 체크 (시스템 정책 포함)
        QuietTimeCheckResult quietCheck = quietTimeService.checkQuietTime(
                message.getUserId(), type);
        response.put("quietTimeCheck", quietCheck);
        
        if (quietCheck.isQuietTime()) {
            log.info("⏰ [사용자별-{}] 금지시간 - 대기열 저장. billId={}, reason={}", 
                    type, message.getBillId(), quietCheck.getReason());
            queueService.addToQueue(message);
            response.put("result", "QUEUED");
            response.put("message", String.format("⏰ 금지 시간입니다 (%s)", quietCheck.getReason()));
            return ResponseEntity.ok(response);
        }
        
        // 3. 발송
        return executeDelivery(message, type, response);
    }
    
    /**
     * 🆕 사용자별 금지시간 적용 발송 (특정 시간)
     */
    private ResponseEntity<Map<String, Object>> sendWithUserPrefAtTime(
            BillingMessageDto message, String type, LocalTime testTime) {
        
        LocalTime actualTime = LocalTime.now();
        log.info("🧪 [사용자별 시간 지정-{}] userId={}, billId={}, testTime={}, actualTime={}", 
                type, message.getUserId(), message.getBillId(), testTime, actualTime);
        
        Map<String, Object> response = new HashMap<>();
        response.put("billId", message.getBillId());
        response.put("userId", message.getUserId());
        response.put("channel", type);
        response.put("simulatedTime", testTime.toString());
        response.put("actualTime", actualTime.toString());
        response.put("testMode", "USER_PREF_TIME_SIMULATION (사용자별 시간 지정)");
        
        // 1. 중복 체크
        if (duplicateCheckHandler.isDuplicate(message.getBillId(), type)) {
            log.warn("⚠️ [사용자별 시간 지정-{}] 중복 차단. billId={}", type, message.getBillId());
            response.put("result", "DUPLICATE_BLOCKED");
            response.put("message", String.format("⚠️ 이미 발송된 %s입니다", type));
            return ResponseEntity.ok(response);
        }
        
        // 2. 지정된 시간 기준으로 사용자별 금지시간 체크
        QuietTimeCheckResult quietCheck = quietTimeService.checkQuietTime(
                message.getUserId(), type, testTime);
        response.put("quietTimeCheck", quietCheck);
        
        if (quietCheck.isQuietTime()) {
            log.info("⏰ [사용자별 시간 지정-{}] 금지시간 - 대기열 저장. billId={}, testTime={}, reason={}", 
                    type, message.getBillId(), testTime, quietCheck.getReason());
            queueService.addToQueue(message);
            response.put("result", "QUEUED");
            response.put("message", String.format("⏰ %s는 금지 시간입니다 (%s)", 
                    testTime, quietCheck.getReason()));
            return ResponseEntity.ok(response);
        }
        
        // 3. 발송
        return executeDelivery(message, type, response);
    }
    
    @Operation(summary = "DLT 테스트 - 간단 버전")
    @PostMapping("/dlt/test-simple")
    public ResponseEntity<Map<String, Object>> testDltSimple(
            @RequestParam Long billId,
            @RequestParam Long userId) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            BillingMessageDto message = BillingMessageDto.builder()
                    .billId(billId)
                    .userId(userId)
                    .recipientEmail("test@test.com")
                    .recipientPhone("010-1234-5678")
                    .totalAmount(85000L)
                    .billYearMonth("2026-01")
                    .dueDate("2026-02-05")
                    .notificationType("EMAIL")
                    .build();
            
          // ✅ JSON 문자열로 변환해서 전송
            ObjectMapper objectMapper = new ObjectMapper();
            String messageJson = objectMapper.writeValueAsString(message);
            
            // DLT 토픽으로 직접 전송 (BillingMessageDto 타입)
            kafkaTemplate.send("billing-event.DLT", billId.toString(), messageJson);
            
            response.put("result", "SUCCESS");
            response.put("message", "✅ DLT 테스트 메시지 전송 완료!");
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("result", "FAILED");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * 실제 발송 실행 (공통 로직)
     */
    private ResponseEntity<Map<String, Object>> executeDelivery(
            BillingMessageDto message, String type, Map<String, Object> response) {
        
        try {
            switch (type) {
                case "EMAIL":
                    emailService.sendEmail(message);
                    log.info("📧 [이메일 발송] to={}, billId={}", message.getRecipientEmail(), message.getBillId());
                    response.put("recipient", message.getRecipientEmail());
                    break;
                    
                case "SMS":
                    log.info("📱 [SMS 발송 시뮬레이션] to={}, billId={}, amount={}원", 
                            message.getRecipientPhone(),
                            message.getBillId(),
                            message.getTotalAmount() != null ? String.format("%,d", message.getTotalAmount()) : "0");
                    response.put("recipient", message.getRecipientPhone());
                    break;
                    
                case "PUSH":
                    log.info("🔔 [Push 발송 시뮬레이션] userId={}, billId={}, amount={}원", 
                            message.getUserId(),
                            message.getBillId(),
                            message.getTotalAmount() != null ? String.format("%,d", message.getTotalAmount()) : "0");
                    response.put("recipient", "userId:" + message.getUserId());
                    break;
            }
            
            duplicateCheckHandler.markAsSent(message.getBillId(), type);
            
            log.info("✅ [직접 발송-{}] 성공. billId={}", type, message.getBillId());
            response.put("result", "SENT");
            response.put("message", String.format("✅ %s가 즉시 발송되었습니다", type));
            
        } catch (Exception e) {
            log.error("❌ [직접 발송-{}] 실패. billId={}", type, message.getBillId(), e);
            response.put("result", "FAILED");
            response.put("message", "❌ 발송 실패: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
        
        return ResponseEntity.ok(response);
    }
}