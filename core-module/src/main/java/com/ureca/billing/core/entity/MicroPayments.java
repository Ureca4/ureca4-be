package com.ureca.billing.core.entity;

import java.time.LocalDateTime;

public class MicroPayments {
    private Long paymentId;
    private Long userId;
    private int amount;
    private String merchantName;
    private PaymentType paymentType;
    private LocalDateTime paymentDate;
    private LocalDateTime createdAt;

    public MicroPayments(Long userId, int amount, String merchantName,
                         PaymentType paymentType, LocalDateTime paymentDate) {
        this.userId = userId;
        this.amount = amount;
        this.merchantName = merchantName;
        this.paymentType = paymentType;
        this.paymentDate = paymentDate;
    }

    // Getter / Setter
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    public Long getUserId() { return userId; }
    public int getAmount() { return amount; }
    public String getMerchantName() { return merchantName; }
    public PaymentType getPaymentType() { return paymentType; }
    public LocalDateTime getPaymentDate() { return paymentDate; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
