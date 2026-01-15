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
}
