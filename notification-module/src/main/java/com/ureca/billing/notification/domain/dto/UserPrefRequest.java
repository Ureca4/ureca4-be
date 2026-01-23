package com.ureca.billing.notification.domain.dto;

import lombok.*;

import java.time.LocalTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 사용자 알림 설정 요청 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "사용자 알림 설정 요청")
public class UserPrefRequest {
    
    @Schema(description = "사용자 ID", example = "1")
    private Long userId;
    
    @Schema(description = "채널 (EMAIL, SMS, PUSH)", example = "EMAIL")
    private String channel;
    
    @Schema(description = "채널 활성화 여부", example = "true")
    private Boolean enabled;
    
    @Schema(description = "우선순위 (1=primary, 2=fallback)", example = "1")
    private Integer priority;
    
    @Schema(description = "금지 시작 시간", example = "22:00")
    private LocalTime quietStart;
    
    @Schema(description = "금지 종료 시간", example = "08:00")
    private LocalTime quietEnd;
    
    // ========================================
    // 선호 발송 시간 
    // ========================================
    
    @Schema(description = "선호 발송일 (1~28, 매월 몇일)", example = "15")
    private Integer preferredDay;
    
    @Schema(description = "선호 발송 시 (0~23)", example = "9")
    private Integer preferredHour;
    
    @Schema(description = "선호 발송 분 (0~59)", example = "0")
    private Integer preferredMinute;
    
    // ========================================
    // 편의 생성자
    // ========================================
    
    /**
     * 금지 시간대만 설정하는 간편 생성자
     */
    public static UserPrefRequest ofQuietTime(Long userId, String channel, 
                                               LocalTime quietStart, LocalTime quietEnd) {
        return UserPrefRequest.builder()
                .userId(userId)
                .channel(channel)
                .quietStart(quietStart)
                .quietEnd(quietEnd)
                .build();
    }
    
    /**
     * 채널 토글용 간편 생성자
     */
    public static UserPrefRequest ofToggle(Long userId, String channel, boolean enabled) {
        return UserPrefRequest.builder()
                .userId(userId)
                .channel(channel)
                .enabled(enabled)
                .build();
    }
    /**
     * 선호 발송 시간 설정용 간편 생성자 (NEW)
     */
    public static UserPrefRequest ofPreferredSchedule(Long userId, String channel,
                                                       Integer day, Integer hour, Integer minute) {
        return UserPrefRequest.builder()
                .userId(userId)
                .channel(channel)
                .preferredDay(day)
                .preferredHour(hour)
                .preferredMinute(minute)
                .build();
    }
}