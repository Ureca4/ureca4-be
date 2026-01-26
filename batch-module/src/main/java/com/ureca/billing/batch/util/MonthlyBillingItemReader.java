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
        
        // MySQL Connector/J는 setFetchSize()에 양수 값을 허용하지 않음
        // 양수 값(예: 100) 설정 시 "Illegal value for setFetchSize()" 에러 발생
        // 허용 값: 0 또는 Integer.MIN_VALUE만 가능
        // JdbcPagingItemReader는 페이지 단위로 데이터를 읽으므로 fetchSize 설정이 필수는 아님
        // 서버 DB에서 에러가 나서 주석처리 합니다.
        // reader.setFetchSize(100); 
        
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

    @Bean
    @StepScope
    public ItemReader<Long> billItemReader(
            @Value("#{jobParameters['billingMonth']}") String billingMonth
    ) {
        JdbcPagingItemReader<Long> reader = new JdbcPagingItemReader<>();
        reader.setDataSource(dataSource);
        reader.setPageSize(1000);

        // 결과 매핑 (bill_id만 추출)
        reader.setRowMapper((rs, rowNum) -> rs.getLong("bill_id"));

        MySqlPagingQueryProvider qp = new MySqlPagingQueryProvider();

        // [핵심 변경 사항]
        // 1. SELECT: 청구서 ID 선택
        qp.setSelectClause("b.bill_id");

        // 2. FROM: 청구서(BILLS)를 기준으로 아웃박스(OUTBOX_EVENTS)를 LEFT JOIN
        //    -> 이렇게 하면 매칭되는 아웃박스가 없으면 o.event_id가 NULL이 됩니다.
        qp.setFromClause("FROM BILLS b LEFT JOIN OUTBOX_EVENTS o ON b.bill_id = o.bill_id");

        // 3. WHERE: '이번 달 청구서' 중에서 AND '아웃박스에 기록이 없는(IS NULL)' 것만 골라라
        qp.setWhereClause("WHERE b.billing_month = :billingMonth AND o.event_id IS NULL");

        // 4. 정렬: 페이징 Reader의 필수 조건
        qp.setSortKeys(Map.of("b.bill_id", Order.ASCENDING));

        reader.setQueryProvider(qp);
        reader.setParameterValues(Map.of("billingMonth", billingMonth));

        return reader;
    }
}

