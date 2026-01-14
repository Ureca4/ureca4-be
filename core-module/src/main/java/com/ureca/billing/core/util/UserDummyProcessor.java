package com.ureca.billing.core.util;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import com.ureca.billing.core.entity.UserStatus;
import com.ureca.billing.core.entity.Users;

import net.datafaker.Faker;

@Component
public class UserDummyProcessor implements ItemProcessor<Long, Users> {

    private final Faker faker = new Faker(new Locale("ko"));;

    @Override
    public Users process(Long seq) {

        String email = encrypt("user" + seq + "@test.com");
        String phone = encrypt("010" + String.format("%08d", seq));

        String name = faker.name().fullName();

        LocalDate birthDate = faker.date()
            .birthday(20, 60)
            .toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDate();

        UserStatus status = UserStatus.ACTIVE;

        return new Users(
            email,
            phone,
            name,
            birthDate,
            status
        );
    }

    // AES-256 흉내
    private String encrypt(String value) {
        return "ENC(" + value + ")";
    }
}