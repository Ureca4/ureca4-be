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
import java.time.YearMonth;
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
                    result.put("preferredDay", response.getPreferredDay());
                    result.put("preferredHour", response.getPreferredHour());
                    result.put("preferredMinute", response.getPreferredMinute());
                    result.put("preferredSchedule", response.getPreferredSchedule());
                    result.put("hasPreferredSchedule", response.getHasPreferredSchedule());
                    return ResponseEntity.ok(result);
                })
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                    "userId", userId,
                    "channel", channel,
                    "exists", false,
                    "message", "설정이 없습니다. 시스템 기본 정책이 적용됩니다.",
                    "systemPolicy", "22:00 ~ 08:00 금지",
                    "defaultSchedule", "즉시 발송"
                    
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
    // 선호 발송 시간 API (NEW)
    // ========================================
    
    @Operation(summary = "3-12. 선호 발송 시간 설정", 
               description = "매월 청구서를 받을 선호 시간 설정 (예: 매월 15일 오전 9시)")
    @PutMapping("/{userId}/{channel}/schedule")
    public ResponseEntity<Map<String, Object>> setPreferredSchedule(
            @PathVariable Long userId,
            @PathVariable String channel,
            @Parameter(description = "발송일 (1~28, 매월 몇일)") @RequestParam Integer day,
            @Parameter(description = "발송 시 (0~23)") @RequestParam Integer hour,
            @Parameter(description = "발송 분 (0~59, 생략 시 0)") @RequestParam(defaultValue = "0") Integer minute) {
        
        log.info("📅 Set preferred schedule. userId={}, channel={}, day={}, hour={}, minute={}", 
                userId, channel, day, hour, minute);
        
        try {
            UserPrefResponse response = quietTimeService.setPreferredSchedule(userId, channel, day, hour, minute);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", String.format("✅ 선호 발송 시간이 설정되었습니다: 매월 %d일 %02d:%02d", day, hour, minute),
                "userId", userId,
                "channel", channel,
                "preferredDay", day,
                "preferredHour", hour,
                "preferredMinute", minute,
                "preferredSchedule", response.getPreferredSchedule()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    @Operation(summary = "3-13. 선호 발송 시간 제거", 
               description = "선호 발송 시간 설정을 제거 (즉시 발송으로 변경)")
    @DeleteMapping("/{userId}/{channel}/schedule")
    public ResponseEntity<Map<String, Object>> removePreferredSchedule(
            @PathVariable Long userId,
            @PathVariable String channel) {
        
        quietTimeService.removePreferredSchedule(userId, channel);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "🗑️ 선호 발송 시간이 제거되었습니다. 청구서가 생성되면 즉시 발송됩니다.",
            "userId", userId,
            "channel", channel
        ));
    }
    
    @Operation(summary = "3-14. 다음 발송 예정 시간 조회", 
               description = "특정 청구 월에 대한 다음 발송 예정 시간 조회")
    @GetMapping("/{userId}/{channel}/next-schedule")
    public ResponseEntity<Map<String, Object>> getNextScheduledTime(
            @PathVariable Long userId,
            @PathVariable String channel,
            @Parameter(description = "청구 월 (YYYY-MM)") @RequestParam(defaultValue = "") String billingMonth) {
        
        YearMonth month = billingMonth.isEmpty() 
                ? YearMonth.now() 
                : YearMonth.parse(billingMonth);
        
        return quietTimeService.getNextScheduledTime(userId, channel, month)
                .map(scheduledTime -> ResponseEntity.ok(Map.<String, Object>of(
                    "success", true,
                    "userId", userId,
                    "channel", channel,
                    "billingMonth", month.toString(),
                    "nextScheduledTime", scheduledTime.toString(),
                    "hasPreferredSchedule", true
                )))
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                    "success", true,
                    "userId", userId,
                    "channel", channel,
                    "billingMonth", month.toString(),
                    "hasPreferredSchedule", false,
                    "message", "선호 발송 시간이 설정되지 않았습니다. 청구서 생성 즉시 발송됩니다."
                )));
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
    
    @Operation(summary = "3-15. 선호 발송 시간 설정된 사용자 목록", 
               description = "선호 발송 시간이 설정된 모든 사용자 조회")
    @GetMapping("/admin/with-schedule")
    public ResponseEntity<Map<String, Object>> getUsersWithPreferredSchedule() {
        List<UserPrefResponse> users = quietTimeService.getUsersWithPreferredSchedule();
        
        return ResponseEntity.ok(Map.of(
            "count", users.size(),
            "users", users
        ));
    }
    
    @Operation(summary = "3-16. 특정 일자 발송 예정 사용자 목록", 
               description = "특정 일자에 청구서 발송 예정인 사용자 조회")
    @GetMapping("/admin/by-day/{day}")
    public ResponseEntity<Map<String, Object>> getUsersByPreferredDay(@PathVariable Integer day) {
        List<UserPrefResponse> users = quietTimeService.getUsersByPreferredDay(day);
        
        return ResponseEntity.ok(Map.of(
            "day", day,
            "count", users.size(),
            "users", users
        ));
    }
}