ALTER TABLE `NOTIFICATIONS`
    ADD COLUMN `bill_id` BIGINT NULL AFTER `user_id`,
  ADD KEY `idx_notifications_bill` (`bill_id`),
  ADD CONSTRAINT `fk_notifications_bill`
    FOREIGN KEY (`bill_id`) REFERENCES `BILLS` (`bill_id`);

-- bill_id가 있는 알림에 대해서만 채널별 1건 보장(멱등)
ALTER TABLE `NOTIFICATIONS`
    ADD UNIQUE KEY `uk_notifications_bill_type` (`bill_id`, `notification_type`);
