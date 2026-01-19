package com.ureca.billing.batch.util;

import java.util.Map;

import javax.sql.DataSource;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class MonthlyBillingItemReader {
	
	private final DataSource dataSource;
	
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
}
