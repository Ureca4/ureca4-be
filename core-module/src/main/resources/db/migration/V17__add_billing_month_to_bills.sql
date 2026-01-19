-- =========================================================
-- Flyway Migration: V17__add_billing_month_to_bills.sql
-- 설명: BILLS 테이블에 청구월 컬럼 추가
-- 작성일: 2025-01-18
-- =========================================================

ALTER TABLE BILLS
ADD COLUMN billing_month CHAR(7) NOT NULL COMMENT 'YYYY-MM',
ADD CONSTRAINT chk_billing_month_format
CHECK (billing_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
ADD UNIQUE KEY uk_user_month (user_id, billing_month);