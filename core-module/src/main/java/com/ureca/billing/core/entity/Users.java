package com.ureca.billing.core.entity;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor  // 기본 생성자 (JPA 사용 시 필수)
@AllArgsConstructor // 모든 필드를 포함하는 생성자 (Builder 사용 시 필요)
@Builder            // 빌더 패턴 적용
public class Users {

    private Long userId;

    private String emailCipher; // AES-256-GCM
    private String emailHash;   // SHA-256

    private String phoneCipher; // AES-256-GCM
    private String phoneHash;   // SHA-256

    private String name;
    private LocalDate birthDate;
    private UserStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}