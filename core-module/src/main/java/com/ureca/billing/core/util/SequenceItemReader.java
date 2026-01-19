package com.ureca.billing.core.util;

import org.springframework.batch.item.ItemStreamReader;

public class SequenceItemReader implements ItemStreamReader<Long> {

    private long current = 1;
    private final long max;

    public SequenceItemReader(long max) {
        this.max = max;
    }
    @Override
    public Long read() {
        if (current > max) return null;
        return current++;
    }
}