package com.ureca.billing.notification.domain.repository;

import com.ureca.billing.notification.domain.entity.Notification;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends CrudRepository<Notification, Long> {

    /**
     * FAILED 상태이면서 재시도 가능한 메시지 조회
     */
    @Query("""
        SELECT * FROM notifications
        WHERE notification_status = 'FAILED'
        AND retry_count < 3
        ORDER BY created_at ASC
    """)
    List<Notification> findFailedMessagesForRetry();
    
    /**
     * 특정 userId와 notification_type으로 조회
     */
    @Query("""
        SELECT * FROM notifications
        WHERE user_id = :userId
        AND notification_type = :notificationType
        ORDER BY created_at DESC
        LIMIT 1
    """)
    Notification findLatestByUserIdAndType(
        @Param("userId") Long userId,
        @Param("notificationType") String notificationType
    );
}