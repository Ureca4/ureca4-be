package com.ureca.billing.notification.domain.dto;

import lombok.*;

import java.time.LocalTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockTimeCheckResponse {
    
    private String currentTime;
    private Boolean isBlockTime;
    private String blockPeriod;  // "22:00 ~ 08:00"
    private String message;
}