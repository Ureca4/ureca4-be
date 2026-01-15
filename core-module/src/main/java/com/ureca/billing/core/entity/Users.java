package com.ureca.billing.core.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class Users {
    private Long userId; // DB에서 auto_increment
    private String email;
    private String phone;
    private String name;
    private LocalDate birthDate;
    private UserStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 생성자
    public Users(String email, String phone, String name,
                 LocalDate birthDate, UserStatus status) {
        this.email = email;
        this.phone = phone;
        this.name = name;
        this.birthDate = birthDate;
        this.status = status;
    }

    // Getter / Setter
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getName() { return name; }
    public LocalDate getBirthDate() { return birthDate; }
    public UserStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
