package com.ureca.billing.notification.consumer.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class DuplicateCheckHandler {
    
    private final RedisTemplate<String, String> redisTemplate;
    
    private static final String SENT_KEY_PREFIX = "sent:msg:EMAIL:";
    private static final long TTL_DAYS = 7; // 7일 후 자동 삭제
    
    /**
     * 중복 발송 체크
     */
    public boolean isDuplicate(Long billId) {
        String key = SENT_KEY_PREFIX + billId;
        Boolean exists = redisTemplate.hasKey(key);
        
        if (Boolean.TRUE.equals(exists)) {
            log.warn("⚠️ Duplicate message detected. billId={}", billId);
            return true;
        }
        
        return false;
    }
    
    /**
     * 발송 완료 마킹
     */
    public void markAsSent(Long billId) {
        String key = SENT_KEY_PREFIX + billId;
        redisTemplate.opsForValue().set(key, "sent", TTL_DAYS, TimeUnit.DAYS);
        log.debug("✅ Marked as sent. billId={}, key={}", billId, key);
    }
}