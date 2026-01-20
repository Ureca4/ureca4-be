package com.ureca.billing.notification.domain.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.*;

import java.time.LocalTime;
import java.time.LocalDateTime;

@Table("message_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MessagePolicy {
    
    @Id
    @Column("policy_id")
    private Long policyId;
    
    @Column("policy_type")
    private String policyType;
    
    @Column("enabled")
    private Boolean enabled;
    
    @Column("start_time")
    private LocalTime startTime;
    
    @Column("end_time")
    private LocalTime endTime;
    
    @Column("description")
    private String description;
    
    @Column("created_at")
    private LocalDateTime createdAt;
    
    @Column("updated_at")
    private LocalDateTime updatedAt;
    
    // 비즈니스 로직
    public boolean isBlockTime(LocalTime currentTime) {
        if (!enabled) {
            return false;
        }
     // 22:00 ~ 08:00 (자정 넘김)
        if (startTime.isAfter(endTime)) {
            return currentTime.isAfter(startTime) || currentTime.isBefore(endTime);
        }
        
        return currentTime.isAfter(startTime) && currentTime.isBefore(endTime);
    }
}