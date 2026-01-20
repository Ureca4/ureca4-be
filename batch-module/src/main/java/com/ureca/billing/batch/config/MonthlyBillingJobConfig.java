package com.ureca.billing.batch.config;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import javax.sql.DataSource;

import com.ureca.billing.core.dto.BillingMessageDto;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;


import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class MonthlyBillingJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    /* =========================
     * Step
     * ========================= */
    @Bean
    public Step monthlyBillingStep(
    		ItemWriter<Long> monthlyBillingWriter,
    		@Qualifier("billingUserReader") ItemReader<Long> monthItemReader
    ){
        return new StepBuilder("monthlyBillingStep", jobRepository)
                .<Long, Long>chunk(100, transactionManager)
                .reader(monthItemReader)
                .writer(monthlyBillingWriter)
                .build();
    }

    //Job
    @Bean
    public Job monthlyBillingJob(JobRepository jobRepository, Step monthlyBillingStep) {
        return new JobBuilder("monthlyBillingJob", jobRepository)
                .start(monthlyBillingStep)
                .build();
    }
}
