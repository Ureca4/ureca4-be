package com.ureca.billing.notification.domain.dto;

import com.ureca.billing.core.dto.BillingMessageDto;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 예약 발송 메시지 DTO
 * - 기존 BillingMessageDto를 확장하여 예약 시간 정보 추가
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ScheduledMessageDto {
    
    // 예약 발송 고유 ID (Redis에서 관리)
    private String scheduleId;
    
    // 예약 발송 시간
    private LocalDateTime scheduledAt;
    
    // 발송 채널 (EMAIL, SMS, PUSH)
    private String channel;
    
    // 예약 상태 (SCHEDULED, PROCESSING, SENT, CANCELLED)
    private String status;
    
    // 등록 시간
    private LocalDateTime createdAt;
    
    // 원본 메시지 정보
    private Long billId;
    private Long userId;
    private String recipientEmail;
    private String recipientPhone;
    private String name;
    private Long totalAmount;
    private String billYearMonth;
    private String planName;
    
    /**
     * BillingMessageDto로부터 예약 메시지 생성
     */
    public static ScheduledMessageDto from(BillingMessageDto billing, LocalDateTime scheduledAt, String channel) {
        return ScheduledMessageDto.builder()
                .scheduleId(generateScheduleId(billing.getBillId(), channel))
                .scheduledAt(scheduledAt)
                .channel(channel)
                .status("SCHEDULED")
                .createdAt(LocalDateTime.now())
                .billId(billing.getBillId())
                .userId(billing.getUserId())
                .recipientEmail(billing.getRecipientEmail())
                .recipientPhone(billing.getRecipientPhone())
                .name(billing.getName())
                .totalAmount(billing.getTotalAmount())
                .billYearMonth(billing.getBillYearMonth())
                .planName(billing.getPlanName())
                .build();
    }
    
    /**
     * BillingMessageDto로 변환 (실제 발송 시 사용)
     */
    public BillingMessageDto toBillingMessage() {
        return BillingMessageDto.builder()
                .billId(this.billId)
                .userId(this.userId)
                .recipientEmail(this.recipientEmail)
                .recipientPhone(this.recipientPhone)
                .name(this.name)
                .totalAmount(this.totalAmount)
                .billYearMonth(this.billYearMonth)
                .planName(this.planName)
                .notificationType(this.channel)
                .timestamp(LocalDateTime.now().toString())
                .build();
    }
    
    /**
     * 예약 ID 생성
     */
    private static String generateScheduleId(Long billId, String channel) {
        return String.format("SCH:%s:%d:%d", channel, billId, System.currentTimeMillis());
    }
}