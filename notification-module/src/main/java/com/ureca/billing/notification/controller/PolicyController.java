package com.ureca.billing.notification.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.ureca.billing.notification.domain.dto.BlockTimeCheckResponse;
import com.ureca.billing.notification.domain.dto.PolicyResponse;
import com.ureca.billing.notification.service.MessagePolicyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;
import java.util.Map;

/**
 * 시스템 발송 정책 Controller
 * 
 * - 시스템 전역 금지 시간대 정책 조회
 * - 현재 금지 상태 확인
 */
@Tag(name = "2. 시스템 정책", description = "시스템 발송 금지 시간대 정책 API")
@RestController
@RequestMapping("/api/policy")
@RequiredArgsConstructor
public class PolicyController {
    
    private final MessagePolicyService policyService;
    
    @Operation(summary = "2-1. EMAIL 정책 조회", 
               description = "이메일 발송 금지 시간대 설정 조회")
    @GetMapping("/email")
    public ResponseEntity<PolicyResponse> getEmailPolicy() {
        PolicyResponse response = policyService.getPolicyInfo();
        return ResponseEntity.ok(response);
    }
    
    @Operation(summary = "2-2. 현재 금지시간 여부 확인", 
               description = "현재 시간이 발송 금지 시간대인지 확인")
    @GetMapping("/check")
    public ResponseEntity<BlockTimeCheckResponse> checkBlockTime() {
        BlockTimeCheckResponse response = policyService.checkBlockTime();
        return ResponseEntity.ok(response);
    }
    
    @Operation(summary = "2-3. 특정 시간 금지 여부 확인", 
               description = "지정한 시간이 발송 금지 시간대인지 확인")
    @GetMapping("/check-at")
    public ResponseEntity<Map<String, Object>> checkBlockTimeAt(
            @RequestParam String time) {
        
        LocalTime checkTime = LocalTime.parse(time);
        boolean isBlock = policyService.isBlockTime(checkTime);
        
        return ResponseEntity.ok(Map.of(
            "checkTime", time,
            "currentTime", LocalTime.now().toString(),
            "isBlockTime", isBlock,
            "blockPeriod", "22:00 ~ 08:00",
            "message", isBlock ? "⛔ 해당 시간은 금지 시간대입니다" : "✅ 해당 시간은 발송 가능합니다"
        ));
    }
}