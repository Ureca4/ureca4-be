-- ============================================================================
-- V12: 메시지 발송 정책 테이블 생성 (지능형 발송 제어)
-- 목적: 발송 금지 시간대 관리
-- ============================================================================

CREATE TABLE message_policy (
    policy_id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '정책 ID',
    policy_type VARCHAR(20) NOT NULL COMMENT '정책 타입 (EMAIL, SMS)',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '정책 활성화 여부',
    start_time TIME NOT NULL COMMENT '발송 금지 시작 시간',
    end_time TIME NOT NULL COMMENT '발송 금지 종료 시간',
    description VARCHAR(255) NULL COMMENT '정책 설명',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='메시지 발송 금지 정책';

-- 유니크 제약
CREATE UNIQUE INDEX uk_message_policy_type ON message_policy(policy_type);

-- 인덱스
CREATE INDEX idx_message_policy_type ON message_policy(policy_type);
CREATE INDEX idx_message_policy_enabled ON message_policy(enabled);