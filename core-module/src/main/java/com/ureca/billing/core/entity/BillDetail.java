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
}
