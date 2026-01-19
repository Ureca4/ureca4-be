-- users 테이블에 notification_type 컬럼 추가
-- 기본값을 'EMAIL'로 설정하여 별도 설정이 없는 유저는 이메일로 알림이 가도록 합니다.
ALTER TABLE users
    ADD COLUMN notification_type VARCHAR(20) DEFAULT 'EMAIL' NOT NULL COMMENT '알림 수신 방법 (PUSH, EMAIL, SMS)';

-- 기존에 데이터가 있었다면 모두 'EMAIL'로 채워지게 됩니다.