-- =========================================================
-- Flyway Migration: V13_alter_plan_and_drop_batch_execution_tables.sql
-- 설명: plans 컬럼 수정 및 배치 관련 테이블 드랍
-- 작성일: 2025-01-15
-- =========================================================
-- PLANS: 요금제 마스터
ALTER TABLE PLANS
	DROP COLUMN sms_limit;

-- BATCH_*: spring batch 실행 시 생성되는 테이블 drop
DROP TABLE IF EXISTS BATCH_EXECUTION_HISTORY;
DROP TABLE IF EXISTS BATCH_EXECUTIONS;