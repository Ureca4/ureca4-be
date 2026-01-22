package com.ureca.billing.batch.util;

import java.time.YearMonth;
import java.util.ArrayList;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ureca.billing.batch.service.MonthlyBillingService;

import lombok.RequiredArgsConstructor;

@Component
@StepScope
@RequiredArgsConstructor
public class MonthlyBillingWriter implements ItemWriter<Long> {

    private final MonthlyBillingService billingService;

    @Value("#{jobParameters['billingMonth']}")
    private String billingMonth;

    @Override
    public void write(Chunk<? extends Long> chunk) {
        if (billingMonth == null) {
            throw new IllegalStateException("billingMonth 파라미터가 없습니다");
        }

        billingService.process(
                new ArrayList<>(chunk.getItems()),
                YearMonth.parse(billingMonth)
        );
    }

}
