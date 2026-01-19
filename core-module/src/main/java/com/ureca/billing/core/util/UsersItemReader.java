package com.ureca.billing.core.util;


import java.util.List;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ureca.billing.core.entity.Users;
import com.ureca.billing.core.mapper.UsersRowMapper;

import lombok.RequiredArgsConstructor;

@Component
@StepScope
@RequiredArgsConstructor
public class UsersItemReader implements ItemReader<Users> {

    private final JdbcTemplate jdbcTemplate;

    private int index = 0;
    private List<Users> users;

    @Override
    public Users read() {
        if (users == null) {
            users = jdbcTemplate.query(
                "SELECT * FROM USERS",
                new UsersRowMapper()
            );
        }

        if (index >= users.size()) return null;
        return users.get(index++);
    }
}
