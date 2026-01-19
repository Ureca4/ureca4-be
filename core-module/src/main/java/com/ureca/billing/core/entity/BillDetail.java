package com.ureca.billing.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillDetail {

    private Long id;                // detail_id
    private Long billId;            // bill_id (FK)
    private String detailType;
    private ChargeCategory chargeCategory;
    private LocalDateTime createdAt;
    private Long relatedUserId;     // 가족 결합 등 참조용 ID
    private Long amount;
    
	public void setId(Long id) {
		this.id = id;
	}
	public void setBillId(Long billId) {
		this.billId = billId;
	}
	public void setDetailType(String detailType) {
		this.detailType = detailType;
	}
	public void setChargeCategory(ChargeCategory chargeCategory) {
		this.chargeCategory = chargeCategory;
	}
	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}
	public void setRelatedUserId(Long relatedUserId) {
		this.relatedUserId = relatedUserId;
	}
	public void setAmount(Long amount) {
		this.amount = amount;
	}
}
