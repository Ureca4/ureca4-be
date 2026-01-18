-- =========================================================
-- Flyway Migration: V18__add_amount_to_bill_details.sql
-- 설명: BILL_DETAILS 테이블에 금액 컬럼 추가
-- 작성일: 2025-01-18
-- =========================================================

ALTER TABLE BILL_DETAILS
ADD COLUMN amount INT NOT NULL AFTER charge_category;
