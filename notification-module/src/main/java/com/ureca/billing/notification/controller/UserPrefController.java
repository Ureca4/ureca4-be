package com.ureca.billing.notification.controller;

import com.ureca.billing.notification.domain.dto.QuietTimeCheckResult;
import com.ureca.billing.notification.domain.dto.UserPrefRequest;
import com.ureca.billing.notification.domain.dto.UserPrefResponse;
import com.ureca.billing.notification.service.UserQuietTimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 사용자 알림 설정 Controller
 * 
 * - 사용자별 채널 설정 (EMAIL/SMS/PUSH)
 * - 사용자별 금지 시간대 설정
 * - 금지 시간 체크
 */
@Tag(name = "3. 사용자 알림 설정", description = "사용자별 알림 설정 및 금지 시간대 관리 API")
@RestController
@RequestMapping("/api/user-prefs")
@RequiredArgsConstructor
@Slf4j
public class UserPrefController {
    
    private final UserQuietTimeService quietTimeService;
    
    // ========================================
    // 금지 시간 체크 API
    // ========================================
    
    @Operation(summary = "3-1. 금지 시간 체크 (현재 시간)", 
               description = "현재 시간이 사용자의 금지 시간대인지 확인 (사용자 설정 + 시스템 정책)")
    @GetMapping("/{userId}/check-quiet")
    public ResponseEntity<QuietTimeCheckResult> checkQuietTime(
            @PathVariable Long userId,
            @Parameter(description = "채널 (EMAIL, SMS, PUSH)") 
            @RequestParam(defaultValue = "EMAIL") String channel) {
        
        QuietTimeCheckResult result = quietTimeService.checkQuietTime(userId, channel);
        return ResponseEntity.ok(result);
    }
    
    @Operation(summary = "3-2. 금지 시간 체크 (특정 시간)", 
               description = "지정한 시간이 사용자의 금지 시간대인지 확인")
    @GetMapping("/{userId}/check-quiet-at")
    public ResponseEntity<QuietTimeCheckResult> checkQuietTimeAt(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "EMAIL") String channel,
            @Parameter(description = "테스트 시간 (HH:mm 형식)") 
            @RequestParam String time) {
        
        LocalTime checkTime = LocalTime.parse(time);
        QuietTimeCheckResult result = quietTimeService.checkQuietTime(userId, channel, checkTime);
        return ResponseEntity.ok(result);
    }
    
    // ========================================
    // 설정 조회 API
    // ========================================
    
    @Operation(summary = "3-3. 사용자 알림 설정 전체 조회", 
               description = "사용자의 모든 채널별 알림 설정 조회")
    @GetMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> getUserPrefs(@PathVariable Long userId) {
        List<UserPrefResponse> prefs = quietTimeService.getUserPrefs(userId);
        
        return ResponseEntity.ok(Map.of(
            "userId", userId,
            "count", prefs.size(),
            "preferences", prefs
        ));
    }
    
    @Operation(summary = "3-4. 특정 채널 설정 조회", 
               description = "사용자의 특정 채널 알림 설정 조회")
    @GetMapping("/{userId}/{channel}")
    public ResponseEntity<Map<String, Object>> getUserPrefByChannel(
            @PathVariable Long userId,
            @PathVariable String channel) {
        
        return quietTimeService.getUserPref(userId, channel)
                .map(pref -> {
                    UserPrefResponse response = UserPrefResponse.from(pref);
                    Map<String, Object> result = new HashMap<>();
                    result.put("userId", response.getUserId());
                    result.put("channel", response.getChannel());
                    result.put("enabled", response.getEnabled());
                    result.put("priority", response.getPriority());
                    result.put("quietStart", response.getQuietStart());
                    result.put("quietEnd", response.getQuietEnd());
                    result.put("quietPeriod", response.getQuietPeriod());
                    result.put("hasQuietTime", response.getHasQuietTime());
                    return ResponseEntity.ok(result);
                })
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                    "userId", userId,
                    "channel", channel,
                    "exists", false,
                    "message", "설정이 없습니다. 시스템 기본 정책이 적용됩니다.",
                    "systemPolicy", "22:00 ~ 08:00 금지"
                )));
    }
    
    // ========================================
    // 설정 저장/수정 API
    // ========================================
    
    @Operation(summary = "3-5. 알림 설정 저장/수정", 
               description = "사용자의 채널별 알림 설정을 생성하거나 수정")
    @PostMapping
    public ResponseEntity<UserPrefResponse> saveOrUpdatePref(@RequestBody UserPrefRequest request) {
        log.info("📝 Save/Update pref request: {}", request);
        
        UserPrefResponse response = quietTimeService.saveOrUpdatePref(request);
        return ResponseEntity.ok(response);
    }
    
    @Operation(summary = "3-6. 금지 시간대 설정", 
               description = "특정 사용자의 채널에 금지 시간대만 설정")
    @PutMapping("/{userId}/{channel}/quiet-time")
    public ResponseEntity<Map<String, Object>> setQuietTime(
            @PathVariable Long userId,
            @PathVariable String channel,
            @Parameter(description = "금지 시작 시간 (HH:mm)") @RequestParam String quietStart,
            @Parameter(description = "금지 종료 시간 (HH:mm)") @RequestParam String quietEnd) {
        
        LocalTime start = LocalTime.parse(quietStart);
        LocalTime end = LocalTime.parse(quietEnd);
        
        quietTimeService.updateQuietTime(userId, channel, start, end);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", String.format("✅ 금지 시간대가 설정되었습니다: %s ~ %s", quietStart, quietEnd),
            "userId", userId,
            "channel", channel,
            "quietStart", quietStart,
            "quietEnd", quietEnd
        ));
    }
    
    @Operation(summary = "3-7. 금지 시간대 제거", 
               description = "사용자의 금지 시간대 설정을 제거 (시스템 정책만 적용)")
    @DeleteMapping("/{userId}/{channel}/quiet-time")
    public ResponseEntity<Map<String, Object>> removeQuietTime(
            @PathVariable Long userId,
            @PathVariable String channel) {
        
        quietTimeService.removeQuietTime(userId, channel);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "🗑️ 금지 시간대가 제거되었습니다. 시스템 정책(22:00~08:00)만 적용됩니다.",
            "userId", userId,
            "channel", channel
        ));
    }
    
    @Operation(summary = "3-8. 채널 활성화/비활성화", 
               description = "특정 채널의 알림 수신 여부 설정")
    @PutMapping("/{userId}/{channel}/toggle")
    public ResponseEntity<Map<String, Object>> toggleChannel(
            @PathVariable Long userId,
            @PathVariable String channel,
            @RequestParam boolean enabled) {
        
        quietTimeService.toggleChannel(userId, channel, enabled);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", enabled ? "✅ 채널이 활성화되었습니다." : "🚫 채널이 비활성화되었습니다.",
            "userId", userId,
            "channel", channel,
            "enabled", enabled
        ));
    }
    
    // ========================================
    // 설정 삭제 API
    // ========================================
    
    @Operation(summary = "3-9. 사용자 알림 설정 전체 삭제", 
               description = "사용자의 모든 알림 설정 삭제 (시스템 기본 정책 적용)")
    @DeleteMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> deleteUserPrefs(@PathVariable Long userId) {
        quietTimeService.deleteUserPrefs(userId);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "🗑️ 사용자의 모든 알림 설정이 삭제되었습니다.",
            "userId", userId
        ));
    }
    
    // ========================================
    // 통계/관리용 API
    // ========================================
    
    @Operation(summary = "3-10. 금지 시간대 설정된 사용자 목록", 
               description = "금지 시간대가 설정된 모든 사용자 조회")
    @GetMapping("/admin/with-quiet-time")
    public ResponseEntity<Map<String, Object>> getUsersWithQuietTime() {
        List<UserPrefResponse> users = quietTimeService.getUsersWithQuietTime();
        
        return ResponseEntity.ok(Map.of(
            "count", users.size(),
            "users", users
        ));
    }
    
    @Operation(summary = "3-11. 채널별 활성 사용자 수", 
               description = "각 채널을 활성화한 사용자 수 조회")
    @GetMapping("/admin/stats")
    public ResponseEntity<Map<String, Object>> getChannelStats() {
        return ResponseEntity.ok(Map.of(
            "EMAIL", quietTimeService.countEnabledUsers("EMAIL"),
            "SMS", quietTimeService.countEnabledUsers("SMS"),
            "PUSH", quietTimeService.countEnabledUsers("PUSH")
        ));
    }
}