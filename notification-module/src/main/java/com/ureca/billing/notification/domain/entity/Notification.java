package com.ureca.billing.notification.domain.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.*;

import java.time.LocalDateTime;

@Table("notifications")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Notification {
    
    @Id
    @Column("notification_id")
    private Long notificationId;
    
    @Column("user_id")
    private Long userId;
    
    @Column("bill_id")
    private Long billId;
    
    @Column("notification_type")
    private String notificationType;  // "EMAIL" or "SMS"
    
    @Column("notification_status")
    private String notificationStatus;  // "PENDING", "WAITING", "SENT", "FAILED", "RETRY"
    
    @Column("recipient")
    private String recipient;
    
    @Column("content")
    private String content;
    
    @Column("retry_count")
    @Builder.Default
    private Integer retryCount = 0;
    
    @Column("scheduled_at")
    private LocalDateTime scheduledAt;
    
    @Column("sent_at")
    private LocalDateTime sentAt;
    
    @Column("error_message")
    private String errorMessage;
    
    @Column("created_at")
    private LocalDateTime createdAt;
    
    // ==============================
    // 재시도 로직용 헬퍼 메서드
    // ==============================
    
    /**
     * 재시도 카운트 증가
     * Spring Data JDBC는 불변 객체를 권장하므로 새 객체 생성
     */
    public Notification incrementRetryCount() {
        return Notification.builder()
            .notificationId(this.notificationId)
            .userId(this.userId)
            .notificationType(this.notificationType)
            .notificationStatus("RETRY")  // 상태를 RETRY로 변경
            .recipient(this.recipient)
            .content(this.content)
            .billId(this.billId) 
            .retryCount(this.retryCount + 1)  // 카운트 증가
            .scheduledAt(this.scheduledAt)
            .sentAt(null)  // 재시도이므로 null
            .errorMessage(this.errorMessage)
            .createdAt(this.createdAt)
            .build();
    }
    
    /**
     * 최종 실패 처리 (3회 재시도 후)
     */
    public Notification markAsFinalFailure(String errorMessage) {
        return Notification.builder()
            .notificationId(this.notificationId)
            .userId(this.userId)
            .notificationType(this.notificationType)
            .notificationStatus("FAILED")
            .billId(this.billId) 
            .recipient(this.recipient)
            .content(this.content)
            .retryCount(this.retryCount)
            .scheduledAt(this.scheduledAt)
            .sentAt(null)
            .errorMessage("Final failure after 3 retries: " + errorMessage)
            .createdAt(this.createdAt)
            .build();
    }
}