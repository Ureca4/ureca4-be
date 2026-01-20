-- Add PUSH to OUTBOX_EVENTS.notification_type enum
ALTER TABLE OUTBOX_EVENTS
    MODIFY notification_type ENUM('EMAIL','SMS','PUSH')
    COLLATE utf8mb4_unicode_ci
    NOT NULL;
