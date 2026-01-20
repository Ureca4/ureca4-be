package com.ureca.billing.notification.service;

import com.ureca.billing.core.entity.NotificationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SmsSender implements NotificationSender {

    @Override
    public NotificationType getType() {
        return NotificationType.SMS;
    }

    @Override
    public boolean send(String phoneNumber, String content) {
        // 요구사항: SMS는 실패 처리 하지 않음
        log.info("[SMS] 발송 완료: {} / 내용: {}", phoneNumber, content);
        return true;
    }
}
