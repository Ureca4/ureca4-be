package com.ureca.billing.core.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

import com.ureca.billing.core.entity.UserStatus;
import com.ureca.billing.core.entity.Users;

public class UsersRowMapper implements RowMapper<Users> {

    @Override
    public Users mapRow(ResultSet rs, int rowNum) throws SQLException {

        Users user = new Users();

        user.setUserId(rs.getLong("user_id"));

        user.setEmailCipher(rs.getString("email_cipher"));
        user.setEmailHash(rs.getString("email_hash"));

        user.setPhoneCipher(rs.getString("phone_cipher"));
        user.setPhoneHash(rs.getString("phone_hash"));

        user.setName(rs.getString("name"));
        user.setBirthDate(rs.getDate("birth_date").toLocalDate());
        user.setStatus(UserStatus.valueOf(rs.getString("status")));

        user.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        user.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());

        return user;
    }
}