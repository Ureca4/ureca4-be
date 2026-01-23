package com.ureca.billing.notification.domain.repository;

import com.ureca.billing.notification.domain.entity.UserNotificationPref;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserNotificationPrefRepository extends CrudRepository<UserNotificationPref, Long> {
    
    /**
     * 사용자 ID로 모든 채널 설정 조회
     */
    @Query("SELECT * FROM user_notification_prefs WHERE user_id = :userId ORDER BY priority ASC")
    List<UserNotificationPref> findAllByUserId(@Param("userId") Long userId);
    
    /**
     * 사용자 ID + 채널로 설정 조회
     */
    @Query("SELECT * FROM user_notification_prefs WHERE user_id = :userId AND channel = :channel")
    Optional<UserNotificationPref> findByUserIdAndChannel(
            @Param("userId") Long userId, 
            @Param("channel") String channel
    );
    
    /**
     * 사용자의 활성화된 채널 목록 조회 (우선순위 순)
     */
    @Query("""
        SELECT * FROM user_notification_prefs 
        WHERE user_id = :userId AND enabled = true 
        ORDER BY priority ASC
    """)
    List<UserNotificationPref> findEnabledByUserId(@Param("userId") Long userId);
    
    /**
     * 특정 채널이 활성화된 사용자 수 조회
     */
    @Query("SELECT COUNT(*) FROM user_notification_prefs WHERE channel = :channel AND enabled = true")
    long countEnabledByChannel(@Param("channel") String channel);
    
    /**
     * 금지 시간대가 설정된 사용자 목록 조회
     */
    @Query("""
        SELECT * FROM user_notification_prefs 
        WHERE quiet_start IS NOT NULL AND quiet_end IS NOT NULL 
        ORDER BY user_id, channel
    """)
    List<UserNotificationPref> findAllWithQuietTime();
    
    /**
     * 사용자 설정 존재 여부 확인
     */
    @Query("SELECT COUNT(*) > 0 FROM user_notification_prefs WHERE user_id = :userId AND channel = :channel")
    boolean existsByUserIdAndChannel(@Param("userId") Long userId, @Param("channel") String channel);
    
    /**
     * 금지 시간대 업데이트
     */
    @Modifying
    @Query("""
        UPDATE user_notification_prefs 
        SET quiet_start = :quietStart, quiet_end = :quietEnd, updated_at = NOW() 
        WHERE user_id = :userId AND channel = :channel
    """)
    void updateQuietTime(
            @Param("userId") Long userId, 
            @Param("channel") String channel,
            @Param("quietStart") LocalTime quietStart,
            @Param("quietEnd") LocalTime quietEnd
    );
    
    /**
     * 채널 활성화/비활성화
     */
    @Modifying
    @Query("""
        UPDATE user_notification_prefs 
        SET enabled = :enabled, updated_at = NOW() 
        WHERE user_id = :userId AND channel = :channel
    """)
    void updateEnabled(
            @Param("userId") Long userId,
            @Param("channel") String channel,
            @Param("enabled") Boolean enabled
    );
    
    /**
     * 사용자의 모든 알림 설정 삭제
     */
    @Modifying
    @Query("DELETE FROM user_notification_prefs WHERE user_id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
    
    
    /**
     * 선호 발송 시간 업데이트
     */
    @Modifying
    @Query("""
        UPDATE user_notification_prefs 
        SET preferred_day = :day, preferred_hour = :hour, preferred_minute = :minute, updated_at = NOW() 
        WHERE user_id = :userId AND channel = :channel
    """)
    void updatePreferredSchedule(
            @Param("userId") Long userId,
            @Param("channel") String channel,
            @Param("day") Integer day,
            @Param("hour") Integer hour,
            @Param("minute") Integer minute
    );
    
    /**
     * 선호 발송 시간 삭제 (즉시 발송으로 변경)
     */
    @Modifying
    @Query("""
        UPDATE user_notification_prefs 
        SET preferred_day = NULL, preferred_hour = NULL, preferred_minute = NULL, updated_at = NOW() 
        WHERE user_id = :userId AND channel = :channel
    """)
    void removePreferredSchedule(
            @Param("userId") Long userId,
            @Param("channel") String channel
    );
    
    /**
     * 선호 발송 시간이 설정된 사용자 목록 조회
     */
    @Query("""
        SELECT * FROM user_notification_prefs 
        WHERE preferred_day IS NOT NULL AND preferred_hour IS NOT NULL 
        AND enabled = true
        ORDER BY user_id, channel
    """)
    List<UserNotificationPref> findAllWithPreferredSchedule();
    
    /**
     * 특정 일자에 발송 예정인 사용자 목록 조회
     * (해당 일에 청구서 발송 예정인 사용자들)
     */
    @Query("""
        SELECT * FROM user_notification_prefs 
        WHERE preferred_day = :day 
        AND enabled = true
        ORDER BY preferred_hour, preferred_minute, user_id
    """)
    List<UserNotificationPref> findByPreferredDay(@Param("day") Integer day);
    
    /**
     * 특정 일/시에 발송 예정인 사용자 목록 조회
     */
    @Query("""
        SELECT * FROM user_notification_prefs 
        WHERE preferred_day = :day AND preferred_hour = :hour
        AND enabled = true
        ORDER BY preferred_minute, user_id
    """)
    List<UserNotificationPref> findByPreferredDayAndHour(
            @Param("day") Integer day,
            @Param("hour") Integer hour
    );
}





