package com.ureca.billing.core.util;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ureca.billing.core.entity.MicroPayments;
import com.ureca.billing.core.entity.PaymentType;

@Component
@StepScope
public class MicroPaymentDummyProcessor implements ItemProcessor<Long, MicroPayments> {

    private final List<Long> userIds;
    private final Random random = new Random();

    public MicroPaymentDummyProcessor(JdbcTemplate jdbcTemplate) {
        // USERS 테이블에서 실제 유저 ID 가져오기
        this.userIds = jdbcTemplate.queryForList(
            "SELECT user_id FROM USERS",
            Long.class
        );
    }

    @Override
    public MicroPayments process(Long seq) {
        // 유저 랜덤 선택
        Long userId = userIds.get(ThreadLocalRandom.current().nextInt(userIds.size()));

        // 결제 금액 랜덤 (예: 100 ~ 10,000)
        int amount = 100 + random.nextInt(9901);

        // 상점 이름 랜덤 예시
        String merchantName = "Merchant_" + (1 + random.nextInt(100));

        // PaymentType enum 랜덤
        PaymentType type = randomEnum(PaymentType.class);

        // 결제일: 오늘 ~ 365일 전
        LocalDateTime paymentDate = LocalDateTime.now();
            //.minusDays(random.nextInt(365));

        return new MicroPayments(
            userId,
            amount,
            merchantName,
            type,
            paymentDate
        );
    }

    // enum 랜덤 선택, 나중에 항목 추가돼도 그대로 동작
    private <T extends Enum<?>> T randomEnum(Class<T> clazz) {
        T[] values = clazz.getEnumConstants();
        return values[random.nextInt(values.length)];
    }
}

