-- V20260119_02__create_user_notification_prefs.sql

CREATE TABLE `USER_NOTIFICATION_PREFS` (
                                           `pref_id`     BIGINT NOT NULL AUTO_INCREMENT,
                                           `user_id`     BIGINT NOT NULL,

                                           `channel`     VARCHAR(20) NOT NULL,          -- EMAIL/SMS/PUSH...
                                           `enabled`     TINYINT(1) NOT NULL DEFAULT 1,
                                           `priority`    INT NOT NULL DEFAULT 1,        -- 1=primary, 2=fallback
                                           `quiet_start` TIME NULL,
                                           `quiet_end`   TIME NULL,

                                           `created_at`  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                           `updated_at`  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                                           PRIMARY KEY (`pref_id`),
                                           UNIQUE KEY `uk_user_channel` (`user_id`, `channel`),
                                           KEY `idx_prefs_user_enabled` (`user_id`, `enabled`),

                                           CONSTRAINT `fk_prefs_user` FOREIGN KEY (`user_id`) REFERENCES `USERS` (`user_id`),

                                           CONSTRAINT `chk_prefs_channel`
                                               CHECK (`channel` IN ('EMAIL','SMS','PUSH'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
