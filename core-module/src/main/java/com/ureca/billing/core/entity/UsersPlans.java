package com.ureca.billing.core.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class UsersPlans {
    private Long userPlanId;
    private Long userId;
    private Long planId;
    private LocalDate startDate;
    private LocalDate endDate;
    private UserPlanStatus status;
    private LocalDateTime createdAt;

    public UsersPlans(Long userId, Long planId, LocalDate startDate,
                     LocalDate endDate, UserPlanStatus status) {
        this.userId = userId;
        this.planId = planId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
    }

    // Getter / Setter
    public Long getUserPlanId() { return userPlanId; }
    public void setUserPlanId(Long userPlanId) { this.userPlanId = userPlanId; }
    public Long getUserId() { return userId; }
    public Long getPlanId() { return planId; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public UserPlanStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
