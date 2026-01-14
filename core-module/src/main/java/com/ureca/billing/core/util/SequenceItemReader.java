package com.ureca.billing.core.util;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.stereotype.Component;

@Component
@StepScope
public class SequenceItemReader implements ItemStreamReader<Long> {

    private long current = 1;
    private final long max = 10_000;

    @Override
    public Long read() {
        if (current > max) return null;
        return current++;
    }
}