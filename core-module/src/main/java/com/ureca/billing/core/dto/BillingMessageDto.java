package com.ureca.billing.core.dto;

import lombok.*;
import java.io.Serializable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class BillingMessageDto implements Serializable {

    // 식별자 & Redis Key
    private Long billId;
    private Long userId;

    // 날짜 정보 (String으로 하되 YYYY-MM-DD 형식 권장)
    private String billYearMonth;      // "202501"
    private String billDate;           // "2025-01-31"
    private String dueDate;            // "2025-02-15"
    private String timestamp;          // 발송 시간

    // 수신자 정보
    private String recipientEmail;
    private String recipientPhone;
    private String notificationType;
    private String name;

    // 금액 정보 (Integer -> Long 변경 추천)
    private Long totalAmount;
    private Long planFee;
    private Long addonFee;
    private Long microPaymentFee;

    // 컨텐츠 정보
    private String planName;
}