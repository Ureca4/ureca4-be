package com.ureca.billing.batch.config;

import java.util.Map;

import javax.sql.DataSource;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.ureca.billing.batch.util.BillingResult;
import com.ureca.billing.core.entity.BillDetail;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class MonthlyBillingJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;

    /* =========================
     * Reader
     * ========================= */
    @Bean
    @StepScope
    public ItemReader<Long> billingUserReader(
            @Value("#{jobParameters['billingMonth']}") String billingMonth
    ) {
        JdbcPagingItemReader<Long> reader = new JdbcPagingItemReader<>();
        reader.setDataSource(dataSource);
        reader.setFetchSize(100);
        reader.setRowMapper((rs, rowNum) -> rs.getLong("user_id"));

        MySqlPagingQueryProvider queryProvider = new MySqlPagingQueryProvider();
        queryProvider.setSelectClause("user_id");
        queryProvider.setFromClause("from USERS");
        queryProvider.setWhereClause("status = 'ACTIVE'");
        queryProvider.setSortKeys(Map.of("user_id", Order.ASCENDING));

        reader.setQueryProvider(queryProvider);
        return reader;
    }
    /* =========================
     * Writer
     * ========================= */
    @Bean
    public ItemWriter<BillingResult> monthlyBillingWriter() {
        return items -> {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

            for (BillingResult result : items) {

                // 1. BILLS 저장
                jdbcTemplate.update(
                    """
                    INSERT INTO BILLS
                    (user_id, billing_month, settlement_date, bill_issue_date)
                    VALUES (?, ?, ?, ?)
                    """,
                    result.getBill().getUserId(),
                    result.getBill().getBillingMonth(),
                    result.getBill().getSettlementDate(),
                    result.getBill().getBillIssueDate()
                );

                Long billId =
                    jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

                // 2. BILL_DETAILS 저장
                for (BillDetail detail : result.getBillDetail()) {
                    jdbcTemplate.update(
                        """
                        INSERT INTO BILL_DETAILS
                        (bill_id, detail_type, charge_category, related_user_id, amount)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        billId,
                        detail.getDetailType(),
                        detail.getChargeCategory().name(),
                        detail.getRelatedUserId(),
                        detail.getAmount()
                    );
                }
            }
        };
    }

    /* =========================
     * Step
     * ========================= */
    @Bean
    public Step monthlyBillingStep(
    		@Qualifier("billingUserReader") ItemReader<Long> billingUserReader,
            ItemProcessor<Long, BillingResult> monthlyBillingProcessor,
            ItemWriter<BillingResult> monthlyBillingWriter
    ) {
        return new StepBuilder("monthlyBillingStep", jobRepository)
                .<Long, BillingResult>chunk(100, transactionManager)
                .reader(billingUserReader)
                .processor(monthlyBillingProcessor)
                .writer(monthlyBillingWriter)
                .build();
    }

    /* =========================
     * Job
     * ========================= */
    @Bean
    public Job monthlyBillingJob(@Qualifier("monthlyBillingStep") Step monthlyBillingStep) {
        return new JobBuilder("monthlyBillingJob", jobRepository)
                .start(monthlyBillingStep)
                .build();
    }
}
