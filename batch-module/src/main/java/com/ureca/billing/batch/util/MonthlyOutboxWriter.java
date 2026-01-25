package com.ureca.billing.batch.util;

import java.util.ArrayList;
import java.util.List;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

import com.ureca.billing.batch.service.MonthlyBillingService;

import lombok.RequiredArgsConstructor;
@Component
@StepScope
@RequiredArgsConstructor
public class MonthlyOutboxWriter implements ItemWriter<Long> {

    private final MonthlyBillingService billingService;

    @Override
    public void write(Chunk<? extends Long> chunk) {
        // Step 2는 청구서 ID(bill_id) 목록을 받아서 알림 이벤트를 생성합니다.
        List<Long> billIds = new ArrayList<>(chunk.getItems());

        billingService.createOutboxEvents(billIds);
    }
}
