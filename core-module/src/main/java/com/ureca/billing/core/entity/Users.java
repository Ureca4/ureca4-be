package com.ureca.billing.core.entity;

import java.time.LocalDate;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Users {

    private Long id;
    private String email;
    private String phone;
    private String name;
    private LocalDate birthDate;
    private UserStatus status;

    public Users(
        String email,
        String phone,
        String name,
        LocalDate birthDate,
        UserStatus status
    ) {
        this.email = email;
        this.phone = phone;
        this.name = name;
        this.birthDate = birthDate;
        this.status = status;
    }
}
