package com.ureca.billing.core.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class UserAddons {
    private Long userAddonId;
    private Long userId;
    private Long addonId;
    private LocalDate startDate;
    private LocalDate endDate;
    private AddonStatus status;
    private LocalDateTime createdAt;

    public UserAddons(Long userId, Long addonId, LocalDate startDate,
                      LocalDate endDate, AddonStatus status) {
        this.userId = userId;
        this.addonId = addonId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
    }

    // Getter / Setter
    public Long getUserAddonId() { return userAddonId; }
    public void setUserAddonId(Long userAddonId) { this.userAddonId = userAddonId; }
    public Long getUserId() { return userId; }
    public Long getAddonId() { return addonId; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public AddonStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
