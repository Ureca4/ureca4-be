package com.ureca.billing.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * JdbcTemplate 쿼리 타임아웃 설정
 * 연결 누수 방지 및 장시간 실행 쿼리 차단을 위해 쿼리 타임아웃을 설정합니다.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        // 쿼리 타임아웃 설정 (60초) - 복잡한 JOIN 쿼리 고려
        jdbcTemplate.setQueryTimeout(60);
        return jdbcTemplate;
    }
}
