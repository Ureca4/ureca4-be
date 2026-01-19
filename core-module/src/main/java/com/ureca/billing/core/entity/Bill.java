package com.ureca.billing.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Bill {

    private Long id;                // bill_id (PK)
    private Long userId;            // user_id (FK) -> 객체 대신 ID값 사용
    private LocalDateTime createdAt;
    private LocalDate settlementDate;
    private LocalDate billIssueDate;
    private String billingMonth;
    
	public void setId(Long id) {
		this.id = id;
	}
	public void setUserId(Long userId) {
		this.userId = userId;
	}
	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}
	public void setSettlementDate(LocalDate settlementDate) {
		this.settlementDate = settlementDate;
	}
	public void setBillIssueDate(LocalDate billIssueDate) {
		this.billIssueDate = billIssueDate;
	}
	public void setBillingMonth(String billingMonth) {
		this.billingMonth = billingMonth;
	}
}
