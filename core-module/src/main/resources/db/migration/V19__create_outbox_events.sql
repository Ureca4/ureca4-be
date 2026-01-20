CREATE TABLE `OUTBOX_EVENTS` (
                                 `outbox_id`      BIGINT NOT NULL AUTO_INCREMENT,
                                 `event_id`       CHAR(36) NOT NULL COMMENT 'UUID',
                                 `bill_id`        BIGINT NOT NULL,
                                 `user_id`        BIGINT NOT NULL,

                                 `event_type`     VARCHAR(100) NOT NULL DEFAULT 'BILLING_NOTIFY',
                                 `notification_type` ENUM('EMAIL','SMS') COLLATE utf8mb4_unicode_ci NOT NULL,

                                 `payload`        JSON NOT NULL,

                                 `status`         ENUM('READY','IN_PROGRESS','PUBLISHED','FAILED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'READY',
                                 `attempt_count`  INT NOT NULL DEFAULT 0,
                                 `next_retry_at`  TIMESTAMP NULL DEFAULT NULL,
                                 `last_error`     TEXT COLLATE utf8mb4_unicode_ci NULL,

    -- Dispatcher 선점(Claim)용
                                 `claim_token`    CHAR(36) NULL,
                                 `locked_by`      VARCHAR(100) COLLATE utf8mb4_unicode_ci NULL,
                                 `locked_at`      TIMESTAMP NULL DEFAULT NULL,

                                 `published_at`   TIMESTAMP NULL DEFAULT NULL,

                                 `created_at`     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 `updated_at`     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                                 PRIMARY KEY (`outbox_id`),

                                 UNIQUE KEY `uk_outbox_event_id` (`event_id`),

    -- 같은 청구서/채널에 대해 outbox 이벤트를 중복 생성하지 않게(배치 중복 실행 방지)
                                 UNIQUE KEY `uk_outbox_bill_type` (`bill_id`, `notification_type`, `event_type`),

    -- Polling 성능
                                 KEY `idx_outbox_poll` (`status`, `next_retry_at`, `outbox_id`),
                                 KEY `idx_outbox_lock` (`status`, `locked_at`),

                                 CONSTRAINT `fk_outbox_bill` FOREIGN KEY (`bill_id`) REFERENCES `BILLS` (`bill_id`),
                                 CONSTRAINT `fk_outbox_user` FOREIGN KEY (`user_id`) REFERENCES `USERS` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

