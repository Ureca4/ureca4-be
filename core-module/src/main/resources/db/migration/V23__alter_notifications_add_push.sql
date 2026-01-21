-- V22: notification_type ENUM에 PUSH 추가
-- Description: 멀티채널 알림 지원을 위해 PUSH 타입 추가

-- notification_type ENUM 수정 (EMAIL, SMS, PUSH)
ALTER TABLE notifications 
MODIFY COLUMN notification_type ENUM('EMAIL', 'SMS', 'PUSH') NOT NULL
COMMENT 'EMAIL, SMS, PUSH 알림 타입';