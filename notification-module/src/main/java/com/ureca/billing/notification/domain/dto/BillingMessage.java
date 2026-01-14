package com.ureca.billing.notification.domain.dto;

import lombok.*;

import java.io.Serializable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString

public class BillingMessage implements Serializable {
    
    private Long billId;
    private Long userId;
    private String billYearMonth;      // 202501
    private String recipientEmail;
    private String recipientPhone;
    private Integer totalAmount;
    private Integer planFee;
    private Integer addonFee;
    private Integer microPaymentFee;
    private String billDate;           // 2025-01-31
    private String dueDate;            // 2025-02-15
    private String planName;
    private String timestamp;
}