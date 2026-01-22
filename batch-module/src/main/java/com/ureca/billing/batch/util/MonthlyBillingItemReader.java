package com.ureca.billing.batch.util;

import java.time.LocalDate;
import java.time.YearMonth;
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
        YearMonth ym = YearMonth.parse(billingMonth);
        LocalDate monthStart = ym.atDay(1);
        LocalDate nextMonthStart = ym.plusMonths(1).atDay(1);

        JdbcPagingItemReader<Long> reader = new JdbcPagingItemReader<>();
        reader.setDataSource(dataSource);
        reader.setFetchSize(1000);
        reader.setPageSize(1000);
        reader.setRowMapper((rs, rowNum) -> rs.getLong("user_id"));

        MySqlPagingQueryProvider qp = new MySqlPagingQueryProvider();
        qp.setSelectClause("u.user_id");
        qp.setFromClause("FROM USERS u");
        qp.setWhereClause("""
            (
                EXISTS (
                    SELECT 1
                    FROM USER_PLANS up
                    WHERE up.user_id = u.user_id
                      AND up.start_date < :nextMonthStart
                      AND (up.end_date IS NULL OR up.end_date >= :monthStart)
                )
                OR
                EXISTS (
                    SELECT 1
                    FROM USER_ADDONS ua
                    WHERE ua.user_id = u.user_id
                      AND ua.start_date < :nextMonthStart
                      AND (ua.end_date IS NULL OR ua.end_date >= :monthStart)
                )
            )
        """);

        qp.setSortKeys(Map.of("u.user_id", Order.ASCENDING));

        reader.setQueryProvider(qp);
        reader.setParameterValues(Map.of(
            "monthStart", monthStart,
            "nextMonthStart", nextMonthStart
        ));

        return reader;
    }
}

