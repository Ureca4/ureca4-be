package com.ureca.billing.batch.config;

import com.ureca.billing.batch.util.MonthlyBillingWriter;
import com.ureca.billing.batch.util.MonthlyOutboxWriter;
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

    private final MonthlyBillingWriter monthlyBillingWriter;         // Step 1용
    private final MonthlyOutboxWriter monthlyOutboxWriter; // Step 2용

    //Step
    @Bean
    public Step monthlyBillingStep(
    		@Qualifier("billingUserReader") ItemReader<Long> monthItemReader
    ){
        return new StepBuilder("monthlyBillingStep", jobRepository)
                .<Long, Long>chunk(1000, transactionManager)
                .reader(monthItemReader)
                .writer(monthlyBillingWriter)
                .build();
    }

    @Bean
    public Step monthlyOutboxStep(
            @Qualifier("billItemReader") ItemReader<Long> billItemReader
    ) {
        return new StepBuilder("monthlyOutboxStep", jobRepository)
                .<Long, Long>chunk(1000, transactionManager)
                .reader(billItemReader)
                .writer(monthlyOutboxWriter) // ✅ 새 Writer 사용 (createOutboxEvents 호출)
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
    		@Qualifier("monthlyBillingStep") Step monthlyBillingStep,
            @Qualifier("monthlyOutboxStep") Step monthlyOutboxStep
    ) {
        return new JobBuilder("monthlyBillingJob", jobRepository)
        		.validator(parameters -> {
                    if (!parameters.getParameters().containsKey("billingMonth")) {
                        throw new JobParametersInvalidException("billingMonth 파라미터는 필수입니다 (yyyy-MM)");
                    }
                })
                .start(monthlyBillingStep)
                .next(monthlyOutboxStep)
                .build();
    }
}
