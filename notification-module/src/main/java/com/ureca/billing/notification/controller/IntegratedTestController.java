package com.ureca.billing.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 통합 테스트 Controller
 * 
 * 기존 TestController + QuietTimeTestController 통합
 * - 이메일 발송 테스트
 * - 시스템 정책 테스트
 * - 사용자별 금지 시간 테스트
 * - 중복 발송 방지
 */
@Tag(name = "1. 통합 발송 테스트", description = "이메일 발송 및 금지시간 통합 테스트 API")
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
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    
    // ========================================
    // 시스템 정책 기반 테스트
    // ========================================
    
    @Operation(summary = "1-1. 이메일 발송 테스트 (시스템 정책)", 
               description = "시스템 금지 시간대(22:00~08:00) 기준으로 발송 테스트")
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> testSend(@RequestBody BillingMessageDto message) {
        LocalTime now = LocalTime.now();
        log.info("🧪 발송 테스트 요청. billId={}, currentTime={}", message.getBillId(), now);
        
        boolean isBlock = policyService.isBlockTime();
        
        if (isBlock) {
            queueService.addToQueue(message);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "action", "QUEUED",
                "message", "⏰ 시스템 금지 시간입니다. 대기열에 저장되었습니다.",
                "currentTime", now.toString(),
                "blockPeriod", "22:00 ~ 08:00"
            ));
        }
        
        return sendEmailAndRespond(message, now.toString(), now.toString());
    }
    
    @Operation(summary = "1-2. 시뮬레이션 시간 발송 테스트 (시스템 정책)",
               description = "특정 시간을 지정하여 시스템 정책 기반 발송 테스트")
    @PostMapping("/send-with-time")
    public ResponseEntity<Map<String, Object>> testSendWithTime(
            @RequestBody BillingMessageDto message,
            @Parameter(description = "시뮬레이션 시간 (HH:mm 형식)") 
            @RequestParam String simulatedTime) {
        
        LocalTime testTime = LocalTime.parse(simulatedTime);
        LocalTime actualTime = LocalTime.now();
        log.info("🧪 시뮬레이션 테스트. simulatedTime={}, actualTime={}", testTime, actualTime);
        
        boolean isBlock = policyService.isBlockTime(testTime);
        
        if (isBlock) {
            queueService.addToQueue(message);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "action", "QUEUED",
                "message", "⏰ 시스템 금지 시간입니다. 대기열에 저장되었습니다.",
                "simulatedTime", testTime.toString(),
                "actualTime", actualTime.toString()
            ));
        }
        
        return sendEmailAndRespond(message, testTime.toString(), actualTime.toString());
    }
    
    @Operation(summary = "1-3. 시스템 정책 체크",
               description = "특정 시간이 시스템 금지 시간대인지 확인")
    @GetMapping("/check-time")
    public ResponseEntity<Map<String, Object>> checkWithTime(
            @Parameter(description = "확인할 시간 (HH:mm 형식)")
            @RequestParam String simulatedTime) {
        
        LocalTime testTime = LocalTime.parse(simulatedTime);
        LocalTime actualTime = LocalTime.now();
        boolean isBlock = policyService.isBlockTime(testTime);
        
        return ResponseEntity.ok(Map.of(
            "simulatedTime", testTime.toString(),
            "actualTime", actualTime.toString(),
            "isBlockTime", isBlock,
            "message", isBlock ? "⛔ 금지 시간" : "✅ 정상 시간",
            "blockPeriod", "22:00 ~ 08:00"
        ));
    }
    
    // ========================================
    // 사용자별 금지 시간 기반 테스트
    // ========================================
    
    @Operation(summary = "1-4. 사용자별 금지 시간 체크",
               description = "사용자 설정 + 시스템 정책 통합 금지 시간 체크")
    @GetMapping("/user-quiet/{userId}")
    public ResponseEntity<QuietTimeCheckResult> checkUserQuietTime(
            @PathVariable Long userId,
            @Parameter(description = "채널 (EMAIL, SMS, PUSH)")
            @RequestParam(defaultValue = "EMAIL") String channel) {
        
        QuietTimeCheckResult result = quietTimeService.checkQuietTime(userId, channel);
        return ResponseEntity.ok(result);
    }
    
    @Operation(summary = "1-5. 사용자별 금지 시간 체크 (시뮬레이션)",
               description = "특정 시간으로 사용자별 금지 시간 체크")
    @GetMapping("/user-quiet/{userId}/at")
    public ResponseEntity<Map<String, Object>> checkUserQuietTimeAt(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "EMAIL") String channel,
            @Parameter(description = "테스트 시간 (HH:mm 형식)")
            @RequestParam String time) {
        
        LocalTime checkTime = LocalTime.parse(time);
        QuietTimeCheckResult result = quietTimeService.checkQuietTime(userId, channel, checkTime);
        
        Map<String, Object> response = new HashMap<>();
        response.put("userId", userId);
        response.put("channel", channel);
        response.put("simulatedTime", time);
        response.put("actualTime", LocalTime.now().toString());
        response.put("checkResult", result);
        
        return ResponseEntity.ok(response);
    }
    
    @Operation(summary = "1-6. 사용자별 금지 시간 적용 발송 테스트",
               description = "사용자 설정 기반 금지 시간 적용하여 발송 테스트")
    @PostMapping("/send-with-user-pref")
    public ResponseEntity<Map<String, Object>> testSendWithUserPref(
            @RequestBody BillingMessageDto message) {
        
        LocalTime now = LocalTime.now();
        log.info("🧪 사용자별 금지시간 적용 발송 테스트. userId={}, billId={}, time={}", 
                message.getUserId(), message.getBillId(), now);
        
        Map<String, Object> response = new HashMap<>();
        response.put("userId", message.getUserId());
        response.put("billId", message.getBillId());
        response.put("currentTime", now.toString());
        
        // 1. 중복 체크
        if (duplicateCheckHandler.isDuplicate(message.getBillId(), "EMAIL")) {
            log.warn("⚠️ 중복 발송 차단. billId={}", message.getBillId());
            response.put("action", "DUPLICATE_BLOCKED");
            response.put("message", "⚠️ 이미 발송된 청구서입니다. 중복 발송이 차단되었습니다.");
            return ResponseEntity.ok(response);
        }
        
        // 2. 금지 시간 체크
        QuietTimeCheckResult quietCheck = quietTimeService.checkQuietTime(
                message.getUserId(), "EMAIL");
        response.put("quietCheck", quietCheck);
        
        if (quietCheck.isQuietTime()) {
            queueService.addToQueue(message);
            
            response.put("action", "QUEUED");
            response.put("message", String.format("⏰ 금지 시간입니다 (%s). 대기열에 저장되었습니다.", 
                    quietCheck.getReason()));
            
        } else {
            try {
                emailService.sendEmail(message);
                duplicateCheckHandler.markAsSent(message.getBillId(), "EMAIL"); 
                response.put("action", "SENT");
                response.put("message", "✅ 이메일이 즉시 발송되었습니다.");
            } catch (Exception e) {
                response.put("action", "FAILED");
                response.put("message", "❌ 발송 실패: " + e.getMessage());
            }
        }
        
        return ResponseEntity.ok(response);
    }
    
    @Operation(summary = "1-7. 사용자별 금지 시간 적용 발송 테스트 (시뮬레이션)",
               description = "특정 시간으로 사용자 설정 기반 발송 테스트")
    @PostMapping("/send-with-user-pref/at")
    public ResponseEntity<Map<String, Object>> testSendWithUserPrefAt(
            @RequestBody BillingMessageDto message,
            @Parameter(description = "시뮬레이션 시간 (HH:mm 형식)")
            @RequestParam String simulatedTime) {
        
        LocalTime checkTime = LocalTime.parse(simulatedTime);
        LocalTime actualTime = LocalTime.now();
        
        log.info("🧪 시뮬레이션 발송 테스트. userId={}, simTime={}, actualTime={}", 
                message.getUserId(), checkTime, actualTime);
        
        QuietTimeCheckResult quietCheck = quietTimeService.checkQuietTime(
                message.getUserId(), "EMAIL", checkTime);
        
        Map<String, Object> response = new HashMap<>();
        response.put("userId", message.getUserId());
        response.put("billId", message.getBillId());
        response.put("simulatedTime", simulatedTime);
        response.put("actualTime", actualTime.toString());
        response.put("quietCheck", quietCheck);
        
        if (quietCheck.isQuietTime()) {
            response.put("action", "WOULD_BE_QUEUED");
            response.put("message", String.format("⏰ 해당 시간은 금지 시간입니다 (%s)", 
                    quietCheck.getReason()));
        } else {
            response.put("action", "WOULD_BE_SENT");
            response.put("message", "✅ 해당 시간은 발송 가능합니다");
        }
        
        return ResponseEntity.ok(response);
    }
    
    // ========================================
    // Private Helper Methods
    // ========================================
    
    private ResponseEntity<Map<String, Object>> sendEmailAndRespond(
            BillingMessageDto message, String simTime, String actualTime) {
        
        // 1. 중복 체크 
        if (duplicateCheckHandler.isDuplicate(message.getBillId(), "EMAIL")) {
            log.warn("⚠️ 중복 발송 차단. billId={}", message.getBillId());
            return ResponseEntity.ok(Map.of(
                "success", false,
                "action", "DUPLICATE_BLOCKED",
                "message", "⚠️ 이미 발송된 청구서입니다. 중복 발송이 차단되었습니다.",
                "billId", message.getBillId(),
                "simulatedTime", simTime,
                "actualTime", actualTime
            ));
        }
        
        try {
            // 2. 이메일 발송
            emailService.sendEmail(message);
            
            // 3. 발송 완료 마킹 
            duplicateCheckHandler.markAsSent(message.getBillId(), "EMAIL");
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "action", "SENT",
                "message", "✅ 이메일이 즉시 발송되었습니다.",
                "billId", message.getBillId(),
                "simulatedTime", simTime,
                "actualTime", actualTime
            ));
            
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "action", "FAILED",
                "message", "❌ 발송 실패: " + e.getMessage(),
                "billId", message.getBillId(),
                "simulatedTime", simTime,
                "actualTime", actualTime
            ));
        }
    }
}