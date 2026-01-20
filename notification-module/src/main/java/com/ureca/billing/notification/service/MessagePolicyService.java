package com.ureca.billing.notification.service;

import com.ureca.billing.notification.domain.dto.BlockTimeCheckResponse;
import com.ureca.billing.notification.domain.dto.PolicyResponse;
import com.ureca.billing.notification.domain.entity.MessagePolicy;
import com.ureca.billing.notification.domain.repository.MessagePolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class MessagePolicyService {
    
    private final MessagePolicyRepository policyRepository;
    
    /**
     * EMAIL 정책 조회 (Redis 캐시 60초)
     */
    @Cacheable(value = "messagePolicy", key = "'EMAIL'", cacheManager = "cacheManager")
    public MessagePolicy getEmailPolicy() {
        log.debug("📋 Fetching EMAIL policy from database");
        return policyRepository.findByPolicyType("EMAIL")
                .orElseThrow(() -> new RuntimeException("EMAIL policy not found"));
    }
    
    /**
     * 현재 시간이 금지 시간대인지 확인
     */
    public boolean isBlockTime() {
        LocalTime now = LocalTime.now();
        return isBlockTime(now);
    }
    
    /**
     * 특정 시간이 금지 시간대인지 확인
     */
    public boolean isBlockTime(LocalTime currentTime) {
        MessagePolicy policy = getEmailPolicy();
        return policy.isBlockTime(currentTime);
    }
    
    /**
     * 정책 정보 조회 (응답용)
     */
    public PolicyResponse getPolicyInfo() {
        MessagePolicy policy = getEmailPolicy();
        return PolicyResponse.from(policy);
    }
    
    /**
     * 현재 시간 금지 여부 체크 (상세 정보 포함)
     */
    public BlockTimeCheckResponse checkBlockTime() {
        LocalTime now = LocalTime.now();
        MessagePolicy policy = getEmailPolicy();
        boolean isBlock = policy.isBlockTime(now);
        
        String message = isBlock 
            ? "⛔ 현재 발송 금지 시간입니다. 메시지가 대기열에 저장됩니다."
            : "✅ 현재 발송 가능한 시간입니다.";
        
        return BlockTimeCheckResponse.builder()
                .currentTime(now.toString())
                .isBlockTime(isBlock)
                .blockPeriod(policy.getStartTime() + " ~ " + policy.getEndTime())
                .message(message)
                .build();
    }
}