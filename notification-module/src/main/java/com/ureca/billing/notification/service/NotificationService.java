package com.ureca.billing.notification.service;

import com.ureca.billing.core.dto.BillingMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final RedisTemplate<String, String> redisTemplate;
    private final Random random = new Random();

    public void sendNotification(BillingMessageDto message){
        // 중복 발송 방지
        String key = "history:billing" + message.getBillId();
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(key, "PROCESSED", Duration.ofDays(1));

        if (Boolean.FALSE.equals(isNew)){
            log.warn("[중복 감지] 이미 처리 된 청구서입니다. ID: {}", message.getBillId());
            return;
        }

        // 이메일 발송 시뮬레이션
        try {
            simulateEmailSending(message.getRecipientEmail());
            log.info("[발송 성공] To: {}, 금액: {}원, 청구서ID: {}", message.getRecipientEmail(), message.getTotalAmount(), message.getBillId());
        } catch(RuntimeException e){
            // 실패 시 Redis 키를 지워야 재시도 가능
            redisTemplate.delete(key);
            throw e;
        }
    }

    // 이메일 발송 Mocking 메소드
    private void simulateEmailSending(String email) {
        log.info("[발송 중] 이메일 서버 연결 시도:{}", email);
        try{
            // 요구사항 1. 1초 지연
            Thread.sleep(1000);
        } catch(InterruptedException e){
            Thread.currentThread().interrupt();
        }

        //  요구사항 2: 1% 확률로 발송 실패 처리
        //  0 ~ 99 사이의 난수 생성. 0이 나오면 실패(1/100 확률)
        if (random.nextInt(100)==0){
            log.error("[장애 주입] 이메일 발송 실패!: {}", email);
            throw new RuntimeException("Email Sending Failed (Simulated)");
        }
    }
}
