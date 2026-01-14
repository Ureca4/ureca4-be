/* =========================================================
   USERS - split email/phone into cipher + hash
========================================================= */

-- 1. 신규 컬럼 추가
ALTER TABLE USERS
    ADD COLUMN email_cipher VARCHAR(255) NOT NULL COMMENT 'AES-256-GCM encrypted email',
    ADD COLUMN email_hash   CHAR(64)     NOT NULL COMMENT 'SHA-256 hash for unique/search',
    ADD COLUMN phone_cipher VARCHAR(255) NOT NULL COMMENT 'AES-256-GCM encrypted phone',
    ADD COLUMN phone_hash   CHAR(64)     NOT NULL COMMENT 'SHA-256 hash for unique/search';

-- 2. UNIQUE 제약 추가 (hash 기준)
ALTER TABLE USERS
    ADD CONSTRAINT uk_users_email_hash UNIQUE (email_hash),
    ADD CONSTRAINT uk_users_phone_hash UNIQUE (phone_hash);

-- 3. 기존 평문 컬럼 제거
ALTER TABLE USERS
    DROP COLUMN email,
    DROP COLUMN phone;
