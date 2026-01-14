-- ============================================================================
-- V15: 메시지 발송 정책 기본 데이터 삽입
-- 목적: 지능형 발송 제어 정책 설정
-- ============================================================================

-- EMAIL 정책 (22:00~08:00 발송 금지)
INSERT INTO message_policy (
    policy_type, 
    enabled, 
    start_time, 
    end_time, 
    description
) VALUES (
    'EMAIL',
    TRUE,
    '22:00:00',
    '08:00:00',
    '심야 시간 청구서 이메일 발송 제한 (사용자 경험 개선)'
);

-- SMS 정책 (발송 제한 없음 - 긴급용)
INSERT INTO message_policy (
    policy_type, 
    enabled, 
    start_time, 
    end_time, 
    description
) VALUES (
    'SMS',
    FALSE,
    '00:00:00',
    '00:00:00',
    'SMS는 긴급 알림용으로 시간 제한 없음'
);