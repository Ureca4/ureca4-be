package com.ureca.billing.notification.domain.repository;

import com.ureca.billing.notification.domain.entity.Notification;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends CrudRepository<Notification, Long> {
    
    /**
     * FAILED 상태이면서 재시도 가능한 메시지 조회
     * retry_count < 3 인 메시지만 조회
     */
    @Query("""
        SELECT * FROM notifications
        WHERE notification_status = 'FAILED'
        AND retry_count < 3
        ORDER BY created_at ASC
    """)
    List<Notification> findFailedMessagesForRetry();
    
    /**
     * ✅ 최대 재시도 횟수 도달한 FAILED 메시지 조회 (DLT 전송 대상)
     * retry_count >= 3 이고 아직 DLT로 안 보낸 것들
     */
    @Query("""
        SELECT * FROM notifications
        WHERE notification_status = 'FAILED'
        AND retry_count >= 3
        AND notification_type = 'EMAIL'
        AND (error_message IS NULL OR error_message NOT LIKE '%DLT%')
        ORDER BY created_at ASC
    """)
    List<Notification> findMaxRetryFailedMessages();
    
    /**
     * 특정 userId와 notification_type으로 최신 Notification 조회
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
    
    /**
     * bill_id로 Notification 조회
     * (V20 마이그레이션에서 bill_id 컬럼 추가됨)
     */
    @Query("""
        SELECT * FROM notifications
        WHERE bill_id = :billId
        ORDER BY created_at DESC
        LIMIT 1
    """)
    Optional<Notification> findByBillId(@Param("billId") Long billId);
    
    /**
     * billId와 notificationType으로 단건 조회 (DLT Consumer용)
     */
    @Query("SELECT * FROM notifications WHERE bill_id = :billId AND notification_type = :type ORDER BY created_at DESC LIMIT 1")
    Optional<Notification> findByBillIdAndType(@Param("billId") Long billId, @Param("type") String type);
    
    /**
     * ✅ billId와 notificationType으로 전체 조회 (테스트 결과 조회용)
     */
    @Query("SELECT * FROM notifications WHERE bill_id = :billId AND notification_type = :type ORDER BY created_at DESC")
    List<Notification> findAllByBillIdAndType(@Param("billId") Long billId, @Param("type") String type);
    
    /**
     * 상태별 Notification 개수 조회
     */
    @Query("""
        SELECT COUNT(*) FROM notifications
        WHERE notification_status = :status
    """)
    long countByStatus(@Param("status") String status);
    
    /**
     * PENDING 상태 (대기열에 있는) Notification 목록 조회
     */
    @Query("""
        SELECT * FROM notifications
        WHERE notification_status = 'PENDING'
        ORDER BY scheduled_at ASC
    """)
    List<Notification> findPendingMessages();
    
    /**
     * RETRY 상태인 Notification 목록 조회
     */
    @Query("""
        SELECT * FROM notifications
        WHERE notification_status = 'RETRY'
        ORDER BY created_at ASC
    """)
    List<Notification> findRetryMessages();
    
    /**
     * 특정 사용자의 모든 Notification 조회
     */
    @Query("""
        SELECT * FROM notifications
        WHERE user_id = :userId
        ORDER BY created_at DESC
    """)
    List<Notification> findAllByUserId(@Param("userId") Long userId);
}