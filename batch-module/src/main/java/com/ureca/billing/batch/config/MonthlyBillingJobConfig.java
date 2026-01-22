package com.ureca.billing.batch.config;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class MonthlyBillingJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    //Step
    @Bean
    public Step monthlyBillingStep(
    		ItemWriter<Long> monthlyBillingWriter,
    		@Qualifier("billingUserReader") ItemReader<Long> monthItemReader
    ){
        return new StepBuilder("monthlyBillingStep", jobRepository)
                .<Long, Long>chunk(1000, transactionManager)
                .reader(monthItemReader)
                .writer(monthlyBillingWriter)
                .build();
    }

    //Job
    /* 파라미터 예시
     --spring.batch.job.name=monthlyBillingJob
		billingMonth=2025-08
    */
    @Bean
    public Job monthlyBillingJob(
    		JobRepository jobRepository, 
    		@Qualifier("monthlyBillingStep") Step monthlyBillingStep
    ) {
        return new JobBuilder("monthlyBillingJob", jobRepository)
        		.validator(parameters -> {
                    if (!parameters.getParameters().containsKey("billingMonth")) {
                        throw new JobParametersInvalidException("billingMonth 파라미터는 필수입니다 (yyyy-MM)");
                    }
                })
                .start(monthlyBillingStep)
                .build();
    }
}
