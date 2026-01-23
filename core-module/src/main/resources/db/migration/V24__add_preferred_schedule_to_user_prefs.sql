-- V22: 사용자 선호 발송 시간 설정 컬럼 추가
-- 매달 청구서를 받을 선호 시간 (일, 시, 분)

ALTER TABLE user_notification_prefs 
ADD COLUMN preferred_day INT NULL COMMENT '선호 발송일 (1~28, NULL이면 즉시발송)';

ALTER TABLE user_notification_prefs 
ADD COLUMN preferred_hour INT NULL COMMENT '선호 발송 시 (0~23)';

ALTER TABLE user_notification_prefs 
ADD COLUMN preferred_minute INT NULL COMMENT '선호 발송 분 (0~59)';

-- 인덱스 추가 (선호 시간이 설정된 사용자 조회용)
CREATE INDEX idx_user_pref_schedule ON user_notification_prefs(preferred_day, preferred_hour);
