package com.ureca.billing.notification.domain.dto;

import com.ureca.billing.notification.domain.entity.UserNotificationPref;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalTime;
import java.time.LocalDateTime;

/**
 * 사용자 알림 설정 응답 DTO
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "사용자 알림 설정 응답")
public class UserPrefResponse {
    
    private Long prefId;
    private Long userId;
    private String channel;
    private Boolean enabled;
    private Integer priority;
    private String quietStart;       // "22:00:00" 형식
    private String quietEnd;         // "08:00:00" 형식
    private String quietPeriod;      // "22:00 ~ 08:00" 형식
    private Boolean hasQuietTime;    // 금지 시간 설정 여부
    
    // ========================================
    // 선호 발송 시간
    // ========================================
    
    @Schema(description = "선호 발송일 (1~28)", example = "15")
    private Integer preferredDay;
    
    @Schema(description = "선호 발송 시 (0~23)", example = "9")
    private Integer preferredHour;
    
    @Schema(description = "선호 발송 분 (0~59)", example = "0")
    private Integer preferredMinute;
    
    @Schema(description = "선호 발송 시간 문자열", example = "매월 15일 09:00")
    private String preferredSchedule;
    
    @Schema(description = "선호 발송 시간 설정 여부")
    private Boolean hasPreferredSchedule;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    /**
     * Entity → DTO 변환
     */
    public static UserPrefResponse from(UserNotificationPref entity) {
        String quietPeriod = null;
        if (entity.getQuietStart() != null && entity.getQuietEnd() != null) {
            quietPeriod = formatTime(entity.getQuietStart()) + " ~ " + formatTime(entity.getQuietEnd());
        }
        
        return UserPrefResponse.builder()
                .prefId(entity.getPrefId())
                .userId(entity.getUserId())
                .channel(entity.getChannel())
                .enabled(entity.getEnabled())
                .priority(entity.getPriority())
                .quietStart(entity.getQuietStart() != null ? entity.getQuietStart().toString() : null)
                .quietEnd(entity.getQuietEnd() != null ? entity.getQuietEnd().toString() : null)
                .quietPeriod(quietPeriod)
                .hasQuietTime(entity.hasQuietTime())
                .preferredDay(entity.getPreferredDay())
                .preferredHour(entity.getPreferredHour())
                .preferredMinute(entity.getPreferredMinute())
                .preferredSchedule(entity.getPreferredScheduleString())
                .hasPreferredSchedule(entity.hasPreferredSchedule())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
    
    private static String formatTime(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }
}