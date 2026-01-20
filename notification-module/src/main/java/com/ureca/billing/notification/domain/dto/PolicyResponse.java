package com.ureca.billing.notification.domain.dto;

import com.ureca.billing.notification.domain.entity.MessagePolicy;
import lombok.*;

import java.time.LocalTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyResponse {
    
    private Long policyId;
    private String policyType;
    private Boolean enabled;
    private String startTime;  // "22:00:00"
    private String endTime;    // "08:00:00"
    private String description;
    
    public static PolicyResponse from(MessagePolicy policy) {
        return PolicyResponse.builder()
                .policyId(policy.getPolicyId())
                .policyType(policy.getPolicyType())
                .enabled(policy.getEnabled())
                .startTime(policy.getStartTime().toString())
                .endTime(policy.getEndTime().toString())
                .description(policy.getDescription())
                .build();
    }
}