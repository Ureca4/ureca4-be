package com.ureca.billing.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.notification.domain.entity.UserNotificationPref;
import com.ureca.billing.notification.domain.repository.UserNotificationPrefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis 기반 사용자 알림 설정 캐시 서비스
 * 
 * 기능:
 * 1. 금지 시간대 캐싱 (Cache-Aside 패턴)
 * 2. 예약 발송 시간 캐싱
 * 3. 사용자 설정 없으면 → 시스템 정책 적용
 * 
 * Redis 키 구조:
 * - user:quiet:{userId}:{channel} → 금지시간 캐시
 * - user:schedule:{userId}:{channel} → 예약시간 캐시
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisUserPrefCache {
    
    private final StringRedisTemplate redisTemplate;
    private final UserNotificationPrefRepository prefRepository;
    private final MessagePolicyService systemPolicyService;  // 시스템 금지시간 폴백
    private final ObjectMapper objectMapper;
    
    private static final String QUIET_TIME_PREFIX = "user:quiet:";
    private static final String SCHEDULE_PREFIX = "user:schedule:";
    private static final long CACHE_TTL_HOURS = 1;  // 캐시 유효시간: 1시간
    
    // ========================================
    // 1. 금지 시간 체크 (Redis 캐싱)
    // ========================================
    
    /**
     * 사용자의 금지 시간대인지 체크
     * 
     * 우선순위:
     * 1. 사용자 설정 있음 → 사용자 설정 적용
     * 2. 사용자 설정 없음 → 시스템 정책(22:00~08:00) 적용
     * 
     * @return QuietTimeResult (isQuiet, reason, source)
     */
    public QuietTimeResult checkQuietTime(Long userId, String channel, LocalTime currentTime) {
        String cacheKey = buildQuietTimeKey(userId, channel);
        
        try {
            // 1. Redis 캐시 조회
            String cachedValue = redisTemplate.opsForValue().get(cacheKey);
            
            if (cachedValue != null) {
                // ✅ 캐시 히트
                log.debug("✅ [Cache Hit] 금지시간 조회: userId={}, channel={}", userId, channel);
                
                // "NONE" → 사용자 설정 없음 → 시스템 정책 폴백
                if ("NONE".equals(cachedValue)) {
                    return checkSystemPolicy(userId, channel, currentTime);
                }
                
                QuietTimeCache cache = objectMapper.readValue(cachedValue, QuietTimeCache.class);
                return evaluateQuietTime(cache, currentTime, userId, channel);
            }
            
            // 2. ❌ 캐시 미스 → DB 조회
            log.debug("❌ [Cache Miss] 금지시간 DB 조회: userId={}, channel={}", userId, channel);
            Optional<UserNotificationPref> prefOpt = prefRepository.findByUserIdAndChannel(userId, channel);
            
            if (prefOpt.isPresent() && prefOpt.get().hasQuietTime()) {
                UserNotificationPref pref = prefOpt.get();
                
                // Redis에 캐싱
                QuietTimeCache cache = new QuietTimeCache(
                    pref.getQuietStart() != null ? pref.getQuietStart().toString() : null,
                    pref.getQuietEnd() != null ? pref.getQuietEnd().toString() : null,
                    pref.getEnabled() != null ? pref.getEnabled() : true
                );
                
                String jsonValue = objectMapper.writeValueAsString(cache);
                redisTemplate.opsForValue().set(cacheKey, jsonValue, CACHE_TTL_HOURS, TimeUnit.HOURS);
                
                log.info("💾 [Cache Set] 금지시간 저장: userId={}, channel={}, {}~{}", 
                    userId, channel, cache.quietStart, cache.quietEnd);
                
                return evaluateQuietTime(cache, currentTime, userId, channel);
            } else {
                // 사용자 설정 없음 → "NONE" 캐싱 (다음번 DB 조회 방지)
                redisTemplate.opsForValue().set(cacheKey, "NONE", CACHE_TTL_HOURS, TimeUnit.HOURS);
                log.debug("💾 [Cache Set] 설정 없음 마커 저장: userId={}, channel={}", userId, channel);
                
                // 시스템 정책으로 폴백
                return checkSystemPolicy(userId, channel, currentTime);
            }
            
        } catch (Exception e) {
            log.error("Redis 조회 실패. DB/시스템 정책 폴백: userId={}, channel={}", 
                userId, channel, e);
            
            // Redis 장애 시 DB 직접 조회 후 시스템 정책 폴백
            return checkQuietTimeWithFallback(userId, channel, currentTime);
        }
    }
    
    /**
     * 금지 시간 평가 (사용자 설정 기준)
     */
    private QuietTimeResult evaluateQuietTime(QuietTimeCache cache, LocalTime currentTime, 
                                               Long userId, String channel) {
        // 채널 비활성화
        if (!cache.enabled) {
            return QuietTimeResult.channelDisabled(userId, channel);
        }
        
        // 금지 시간 미설정 → 시스템 정책 폴백
        if (cache.quietStart == null || cache.quietEnd == null) {
            return checkSystemPolicy(userId, channel, currentTime);
        }
        
        LocalTime start = LocalTime.parse(cache.quietStart);
        LocalTime end = LocalTime.parse(cache.quietEnd);
        
        // 자정 넘김 (22:00 ~ 08:00)
        boolean isQuiet;
        if (start.isAfter(end)) {
            isQuiet = currentTime.isAfter(start) || currentTime.isBefore(end);
        } else {
            isQuiet = currentTime.isAfter(start) && currentTime.isBefore(end);
        }
        
        if (isQuiet) {
            return QuietTimeResult.userQuietTime(userId, channel, start, end);
        } else {
            return QuietTimeResult.allowed(userId, channel, "USER_PREF");
        }
    }
    
    /**
     * 시스템 금지 정책 체크 (폴백)
     */
    private QuietTimeResult checkSystemPolicy(Long userId, String channel, LocalTime currentTime) {
        boolean isSystemBlock = systemPolicyService.isBlockTime(currentTime);
        
        if (isSystemBlock) {
            log.debug("🏢 [System Policy] 시스템 금지시간. userId={}, channel={}", userId, channel);
            return QuietTimeResult.systemQuietTime(userId, channel);
        } else {
            return QuietTimeResult.allowed(userId, channel, "SYSTEM_POLICY");
        }
    }
    
    /**
     * Redis 장애 시 폴백 (DB 조회 → 시스템 정책)
     */
    private QuietTimeResult checkQuietTimeWithFallback(Long userId, String channel, LocalTime currentTime) {
        try {
            Optional<UserNotificationPref> prefOpt = prefRepository.findByUserIdAndChannel(userId, channel);
            
            if (prefOpt.isPresent() && prefOpt.get().hasQuietTime()) {
                UserNotificationPref pref = prefOpt.get();
                boolean isQuiet = pref.isQuietTime(currentTime);
                
                if (isQuiet) {
                    return QuietTimeResult.userQuietTime(userId, channel, 
                        pref.getQuietStart(), pref.getQuietEnd());
                } else {
                    return QuietTimeResult.allowed(userId, channel, "USER_PREF");
                }
            }
        } catch (Exception e) {
            log.error("DB 조회도 실패. 시스템 정책 적용", e);
        }
        
        // 최종 폴백: 시스템 정책
        return checkSystemPolicy(userId, channel, currentTime);
    }
    
    // ========================================
    // 2. 예약 발송 시간 체크 (Redis 캐싱)
    // ========================================
    
    /**
     * 사용자의 예약 발송 시간 조회
     * 
     * @return Optional<LocalDateTime> 예약 시간 (없으면 empty → 즉시 발송)
     */
    public Optional<LocalDateTime> getScheduledTime(Long userId, String channel, YearMonth billingMonth) {
        String cacheKey = buildScheduleKey(userId, channel);
        
        try {
            // 1. Redis 캐시 조회
            String cachedValue = redisTemplate.opsForValue().get(cacheKey);
            
            if (cachedValue != null) {
                log.debug("✅ [Cache Hit] 예약시간 조회: userId={}, channel={}", userId, channel);
                
                // "NONE" → 예약 설정 없음 → 즉시 발송
                if ("NONE".equals(cachedValue)) {
                    return Optional.empty();
                }
                
                ScheduleCache cache = objectMapper.readValue(cachedValue, ScheduleCache.class);
                return Optional.of(calculateScheduledTime(cache, billingMonth));
            }
            
            // 2. 캐시 미스 → DB 조회
            log.debug("❌ [Cache Miss] 예약시간 DB 조회: userId={}, channel={}", userId, channel);
            Optional<UserNotificationPref> prefOpt = prefRepository.findByUserIdAndChannel(userId, channel);
            
            if (prefOpt.isPresent() && prefOpt.get().hasPreferredSchedule()) {
                UserNotificationPref pref = prefOpt.get();
                
                // Redis에 캐싱
                ScheduleCache cache = new ScheduleCache(
                    pref.getPreferredDay(),
                    pref.getPreferredHour(),
                    pref.getPreferredMinute() != null ? pref.getPreferredMinute() : 0
                );
                
                String jsonValue = objectMapper.writeValueAsString(cache);
                redisTemplate.opsForValue().set(cacheKey, jsonValue, CACHE_TTL_HOURS, TimeUnit.HOURS);
                
                log.info("💾 [Cache Set] 예약시간 저장: userId={}, channel={}, 매월 {}일 {:02d}:{:02d}", 
                    userId, channel, cache.day, cache.hour, cache.minute);
                
                return Optional.of(calculateScheduledTime(cache, billingMonth));
            } else {
                // 예약 설정 없음 → "NONE" 캐싱
                redisTemplate.opsForValue().set(cacheKey, "NONE", CACHE_TTL_HOURS, TimeUnit.HOURS);
                return Optional.empty();
            }
            
        } catch (Exception e) {
            log.error("Redis 조회 실패. DB 직접 조회로 폴백: userId={}, channel={}", 
                userId, channel, e);
            
            // Redis 장애 시 DB 직접 조회
            return getScheduledTimeFromDb(userId, channel, billingMonth);
        }
    }
    
    /**
     * 예약 시간 계산
     */
    private LocalDateTime calculateScheduledTime(ScheduleCache cache, YearMonth billingMonth) {
        // 해당 월의 일수 고려 (2월 28일 등)
        int day = Math.min(cache.day, billingMonth.lengthOfMonth());
        return billingMonth.atDay(day).atTime(cache.hour, cache.minute);
    }
    
    /**
     * DB 직접 조회 (폴백)
     */
    private Optional<LocalDateTime> getScheduledTimeFromDb(Long userId, String channel, YearMonth billingMonth) {
        try {
            return prefRepository.findByUserIdAndChannel(userId, channel)
                .filter(UserNotificationPref::hasPreferredSchedule)
                .map(pref -> pref.getNextScheduledTime(billingMonth));
        } catch (Exception e) {
            log.error("DB 예약시간 조회 실패", e);
            return Optional.empty();
        }
    }
    
    // ========================================
    // 3. 캐시 무효화
    // ========================================
    
    /**
     * 사용자 설정 변경 시 캐시 무효화
     */
    public void evictUserPref(Long userId, String channel) {
        String quietKey = buildQuietTimeKey(userId, channel);
        String scheduleKey = buildScheduleKey(userId, channel);
        
        redisTemplate.delete(quietKey);
        redisTemplate.delete(scheduleKey);
        
        log.info("🗑️ [Cache Evict] 사용자 설정 캐시 삭제: userId={}, channel={}", userId, channel);
    }
    
    /**
     * 사용자의 모든 채널 캐시 무효화
     */
    public void evictAllUserPref(Long userId) {
        for (String channel : new String[]{"EMAIL", "SMS", "PUSH"}) {
            evictUserPref(userId, channel);
        }
    }
    
    // ========================================
    // 4. Helper Methods
    // ========================================
    
    private String buildQuietTimeKey(Long userId, String channel) {
        return QUIET_TIME_PREFIX + userId + ":" + channel;
    }
    
    private String buildScheduleKey(Long userId, String channel) {
        return SCHEDULE_PREFIX + userId + ":" + channel;
    }
    
    // ========================================
    // 5. Cache DTOs (Inner Classes)
    // ========================================
    
    /**
     * Redis에 저장할 금지시간 캐시
     */
    public static class QuietTimeCache {
        public String quietStart;
        public String quietEnd;
        public boolean enabled;
        
        public QuietTimeCache() {}
        
        public QuietTimeCache(String quietStart, String quietEnd, boolean enabled) {
            this.quietStart = quietStart;
            this.quietEnd = quietEnd;
            this.enabled = enabled;
        }
    }
    
    /**
     * Redis에 저장할 예약시간 캐시
     */
    public static class ScheduleCache {
        public int day;
        public int hour;
        public int minute;
        
        public ScheduleCache() {}
        
        public ScheduleCache(int day, int hour, int minute) {
            this.day = day;
            this.hour = hour;
            this.minute = minute;
        }
    }
    
    /**
     * 금지시간 체크 결과
     */
    public static class QuietTimeResult {
        public final boolean isQuiet;
        public final String reason;
        public final String source;  // USER_PREF, SYSTEM_POLICY
        public final Long userId;
        public final String channel;
        public final LocalTime quietStart;
        public final LocalTime quietEnd;
        
        private QuietTimeResult(boolean isQuiet, String reason, String source, Long userId, 
                                String channel, LocalTime quietStart, LocalTime quietEnd) {
            this.isQuiet = isQuiet;
            this.reason = reason;
            this.source = source;
            this.userId = userId;
            this.channel = channel;
            this.quietStart = quietStart;
            this.quietEnd = quietEnd;
        }
        
        public static QuietTimeResult userQuietTime(Long userId, String channel, 
                                                     LocalTime start, LocalTime end) {
            return new QuietTimeResult(true, "USER_QUIET_TIME", "USER_PREF", 
                userId, channel, start, end);
        }
        
        public static QuietTimeResult systemQuietTime(Long userId, String channel) {
            return new QuietTimeResult(true, "SYSTEM_QUIET_TIME", "SYSTEM_POLICY", 
                userId, channel, LocalTime.of(22, 0), LocalTime.of(8, 0));
        }
        
        public static QuietTimeResult channelDisabled(Long userId, String channel) {
            return new QuietTimeResult(true, "CHANNEL_DISABLED", "USER_PREF", 
                userId, channel, null, null);
        }
        
        public static QuietTimeResult allowed(Long userId, String channel, String source) {
            return new QuietTimeResult(false, "ALLOWED", source, 
                userId, channel, null, null);
        }
        
        public String getMessage() {
            switch (reason) {
                case "USER_QUIET_TIME":
                    return String.format("사용자 금지시간 (%s ~ %s)", quietStart, quietEnd);
                case "SYSTEM_QUIET_TIME":
                    return "시스템 금지시간 (22:00 ~ 08:00)";
                case "CHANNEL_DISABLED":
                    return "채널 비활성화됨";
                case "ALLOWED":
                    return "발송 가능";
                default:
                    return "알 수 없음";
            }
        }
    }
}