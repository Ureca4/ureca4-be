package com.ureca.billing.notification.service;

import com.ureca.billing.core.entity.NotificationType;

public interface NotificationSender {
    // 발송 타입을 반환 (EMAIL, SMS, PUSH)
    NotificationType getType();

    // 실제 발송 로직 (성공 여부 반환)
    boolean send(String target, String content);
}
