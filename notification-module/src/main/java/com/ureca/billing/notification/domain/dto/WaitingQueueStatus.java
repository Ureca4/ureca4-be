package com.ureca.billing.notification.domain.dto;

import lombok.*;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WaitingQueueStatus {
    
    private Long totalCount;
    private String queueKey;
    private Long readyCount;  // 발송 가능한 메시지 수
    private List<String> readyMessages;  // 발송 가능한 메시지 (최대 10개)
}