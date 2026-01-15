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
public class BillArrears {

    private Long id;                // arrears_id
    private Long userId;            // user_id (FK)
    private Long billId;            // bill_id (FK)
    private Integer arrearsAmount;
    private ArrearsStatus arrearsStatus;
    private LocalDate dueDate;
    private LocalDate paidDate;
    private LocalDateTime createdAt;
}
