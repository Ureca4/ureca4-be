package com.ureca.billing.notification.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 예약 발송 요청 DTO
 * - Swagger에서 한 번에 요청할 수 있도록 통합
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "예약 발송 요청")
public class ScheduleRequest {
    
    // 청구서 정보
    @Schema(description = "청구서 ID", example = "1001")
    private Long billId;
    
    @Schema(description = "사용자 ID", example = "1")
    private Long userId;
    
    @Schema(description = "수신자 이메일", example = "test@yopmail.com")
    private String recipientEmail;
    
    @Schema(description = "수신자 전화번호", example = "01012345678")
    private String recipientPhone;
    
    @Schema(description = "수신자 이름", example = "홍길동")
    private String name;
    
    @Schema(description = "총 청구 금액", example = "55000")
    private Long totalAmount;
    
    @Schema(description = "청구 년월", example = "202501")
    private String billYearMonth;
    
    @Schema(description = "요금제 이름", example = "5G 프리미어 에센셜")
    private String planName;
    
    // 예약 정보
    @Schema(description = "발송 예약 시간", example = "2025-01-23T10:00:00")
    private LocalDateTime scheduledAt;
    
    @Schema(description = "발송 채널 (EMAIL, SMS)", example = "EMAIL")
    private String channel;
}