package com.ureca.billing.core.config;

import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;

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
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.ureca.billing.core.entity.MicroPayments;
import com.ureca.billing.core.entity.UserAddons;
import com.ureca.billing.core.entity.UserNotificationPrefs;
import com.ureca.billing.core.entity.UserPlans;
import com.ureca.billing.core.entity.Users;
import com.ureca.billing.core.util.SequenceItemReader;
import com.ureca.billing.core.util.UserDummyProcessor;
import com.ureca.billing.core.util.UserNotificationPrefsDummyProcessor;
import com.ureca.billing.core.util.UserPlanDummyProcessor;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class DummyDataJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    
    //Users 더미데이터 개수
    @Bean
    @StepScope
    public ItemReader<Long> usersReader() {
        return new SequenceItemReader(10_000);
    }
    //userAddons 더미데이터 개수
    @Bean
    @StepScope
    public ItemReader<Long> userAddonsReader() {
        return new SequenceItemReader(2000000);
    }
    //microPayments 더미데이터 개수
    @Bean
    @StepScope
    public ItemReader<Long> microPaymentsUserReader() {
        return new SequenceItemReader(2000000);
    }
    
    //범용 Step 생성 메서드
    private <I, O> Step createStep(String stepName,
                                   ItemReader<I> reader,
                                   ItemProcessor<I, O> processor,
                                   ItemWriter<O> writer,
                                   int chunkSize) {
        return new StepBuilder(stepName, jobRepository)
                .<I, O>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
    }

    //Users 더미 Step
    @Bean
    public Step usersDummyStep(UserDummyProcessor processor, @Qualifier("usersReader") ItemReader<Long> reader) {
        JdbcBatchItemWriter<Users> writer = new JdbcBatchItemWriter<>();
        writer.setDataSource(dataSource);
        writer.setSql("""
        INSERT INTO USERS (email_cipher, email_hash, phone_cipher, phone_hash, name, birth_date, status)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    """);

        writer.setItemPreparedStatementSetter((user, ps) -> {
            ps.setString(1, user.getEmailCipher());
            ps.setString(2, user.getEmailHash());
            ps.setString(3, user.getPhoneCipher());
            ps.setString(4, user.getPhoneHash());
            ps.setString(5, user.getName());
            ps.setDate(6, Date.valueOf(user.getBirthDate()));
            ps.setString(7, user.getStatus().name());
        });
        writer.afterPropertiesSet();

        return createStep("usersDummyStep", reader, processor, writer, 1000);
    }

    //UserPlans 더미 Step
    @Bean
    public Step userPlansDummyStep(UserPlanDummyProcessor processor, ItemReader<Users> reader) {
        JdbcBatchItemWriter<UserPlans> writer = new JdbcBatchItemWriter<>();
        writer.setDataSource(dataSource);
        writer.setSql("""
            INSERT INTO USER_PLANS (user_id, plan_id, start_date, end_date, status)
            VALUES (?, ?, ?, ?, ?)
        """);
        writer.setItemPreparedStatementSetter((plan, ps) -> {
            ps.setLong(1, plan.getUserId());
            ps.setLong(2, plan.getPlanId());
            ps.setDate(3, Date.valueOf(plan.getStartDate()));
            ps.setDate(4, plan.getEndDate() != null ? Date.valueOf(plan.getEndDate()) : null);
            ps.setString(5, plan.getStatus().name());
        });
        writer.afterPropertiesSet();

        return createStep("userPlansDummyStep", reader, processor, writer, 1000);
    }
    //UserAddons 더미 Step
    @Bean
    public Step userAddonsDummyStep(
            ItemProcessor<Long, UserAddons> processor,
            @Qualifier("userAddonsReader") ItemReader<Long> reader
    ) {
        JdbcBatchItemWriter<UserAddons> writer = new JdbcBatchItemWriter<>();
        writer.setDataSource(dataSource);
        writer.setSql("""
            INSERT INTO USER_ADDONS
            (user_id, addon_id, start_date, end_date, status)
            VALUES (?, ?, ?, ?, ?)
        """);

        writer.setItemPreparedStatementSetter((addon, ps) -> {
            ps.setLong(1, addon.getUserId());
            ps.setLong(2, addon.getAddonId());
            ps.setDate(3, Date.valueOf(addon.getStartDate()));
            ps.setDate(
                4,
                addon.getEndDate() != null
                    ? Date.valueOf(addon.getEndDate())
                    : null
            );
            ps.setString(5, addon.getStatus().name());
        });

        writer.afterPropertiesSet();

        return new StepBuilder("userAddonsDummyStep", jobRepository)
            .<Long, UserAddons>chunk(1000, transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .build();
    }
    //MicroPayments 더미 Step
    @Bean
    public Step microPaymentsDummyStep(
    		@Qualifier("microPaymentsUserReader") ItemReader<Long> reader,
            ItemProcessor<Long, MicroPayments> processor,
            PlatformTransactionManager transactionManager
    ) {
    	JdbcBatchItemWriter<MicroPayments> writer = new JdbcBatchItemWriter<>();
        writer.setDataSource(dataSource);
        writer.setSql("""
            INSERT INTO MICRO_PAYMENTS
            (user_id, amount, merchant_name, payment_type, payment_date)
            VALUES (?, ?, ?, ?, ?)
        """);
        writer.setItemPreparedStatementSetter((payment, ps) -> {
            ps.setLong(1, payment.getUserId());
            ps.setInt(2, payment.getAmount());
            ps.setString(3, payment.getMerchantName());
            ps.setString(4, payment.getPaymentType().name());
            ps.setTimestamp(5, Timestamp.valueOf(payment.getPaymentDate()));
        });
        writer.afterPropertiesSet();
    	
        return new StepBuilder("microPaymentsDummyStep", jobRepository)
                .<Long, MicroPayments>chunk(1000, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
    }
    //UserNotificationPrefs 더미 Step
    @Bean
    public Step userNotificationPrefsDummyStep(
            ItemReader<Users> reader,
            UserNotificationPrefsDummyProcessor processor
    ) {
        JdbcBatchItemWriter<UserNotificationPrefs> writer =
            new JdbcBatchItemWriter<>();

        writer.setDataSource(dataSource);
        writer.setSql("""
            INSERT INTO USER_NOTIFICATION_PREFS
            (user_id, channel, enabled, priority,
             quiet_start, quiet_end, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """);

        writer.setItemPreparedStatementSetter((prefs, ps) -> {
            ps.setLong(1, prefs.getUserId());
            ps.setString(2, prefs.getChannel());
            ps.setBoolean(3, prefs.getEnabled());
            ps.setInt(4, prefs.getPriority());

            if (prefs.getQuietStart() != null) {
                ps.setTime(5, prefs.getQuietStart());
                ps.setTime(6, prefs.getQuietEnd());
            } else {
                ps.setNull(5, Types.TIME);
                ps.setNull(6, Types.TIME);
            }

            ps.setTimestamp(7, Timestamp.valueOf(prefs.getCreatedAt()));
            ps.setTimestamp(8, Timestamp.valueOf(prefs.getUpdatedAt()));
        });

        writer.afterPropertiesSet();

        return new StepBuilder("userNotificationPrefsDummyStep", jobRepository)
            .<Users, UserNotificationPrefs>chunk(1000, transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .build();
    }

    //user 관련 더미데이터 생성 job
    //monthlyDummyDataJob 실행전 반드시 먼저 실행해야됨
    //--spring.batch.job.name=userDummyDataJob
    @Bean
    public Job userDummyDataJob(
    		@Qualifier("usersDummyStep")Step usersDummyStep,
    		@Qualifier("userNotificationPrefsDummyStep") Step userNotificationPrefsDummyStep
    ) {
    	return new JobBuilder("userDummyDataJob", jobRepository)
    			.start(usersDummyStep)
                .next(userNotificationPrefsDummyStep)
    			.build();
    }
    //결제 데이터 관련 더미데이터 생성 job
    //파라미터 필요 
    /*예)
     	--spring.batch.job.name=monthlyDummyDataJob
		argetYearMonth=2025-08
     */
    @Bean
    public Job monthlyDummyDataJob(
    		@Qualifier("userPlansDummyStep") Step userPlansDummyStep,
    		@Qualifier("userAddonsDummyStep") Step userAddonsDummyStep,
    		@Qualifier("microPaymentsDummyStep") Step microPaymentsDummyStep
    		) {
        return new JobBuilder("monthlyDummyDataJob", jobRepository)
                .start(userPlansDummyStep)
                .next(userAddonsDummyStep)
                .next(microPaymentsDummyStep)
                .build();
    }
}
