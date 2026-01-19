package com.ureca.billing.notification.service;

import com.ureca.billing.core.entity.NotificationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Random;

@Slf4j
@Component
public class EmailSender implements NotificationSender {

    @Override
    public NotificationType getType() {
        return NotificationType.EMAIL;
    }

    @Override
    public boolean send(String email, String content) {
        try {
            log.info("[Email] 발송 시도: {}", email);

            // 요구사항: 1초 Delay
            Thread.sleep(1000);

            // 요구사항: 1% 확률로 발송 실패
            if (new Random().nextInt(100) < 1) { // 0~99 중 0이 나오면 실패
                log.error("[Email] 발송 실패 (1% 확률 당첨): {}", email);
                return false;
            }

            log.info("[Email] 발송 완료: {}", email);
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
