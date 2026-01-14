package com.ureca.billing.core.config;

import java.sql.Date;

import javax.sql.DataSource;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.ureca.billing.core.entity.Users;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class UserDummyJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    
    @Bean
    public JdbcBatchItemWriter<Users> userWriter(DataSource dataSource) {
        JdbcBatchItemWriter<Users> writer = new JdbcBatchItemWriter<>();

        writer.setDataSource(dataSource);
        writer.setSql("""
            INSERT INTO USERS
            (email, phone, name, birth_date, status)
            VALUES (?, ?, ?, ?, ?)
        """);

        writer.setItemPreparedStatementSetter((user, ps) -> {
            ps.setString(1, user.getEmail());
            ps.setString(2, user.getPhone());
            ps.setString(3, user.getName());
            ps.setDate(4, Date.valueOf(user.getBirthDate()));
            ps.setString(5, user.getStatus().name()); // ⭐ 핵심
        });

        return writer;
    }

    @Bean
    public Step userDummyStep(
        ItemReader<Long> reader,
        ItemProcessor<Long, Users> processor,
        ItemWriter<Users> writer
    ) {
        return new StepBuilder("userDummyStep", jobRepository)
            .<Long, Users>chunk(1000, transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .build();
    }


    @Bean
    public Job userDummyJob(Step userDummyStep) {
        return new JobBuilder("userDummyJob", jobRepository)
            .start(userDummyStep)
            .build();
    }
}
