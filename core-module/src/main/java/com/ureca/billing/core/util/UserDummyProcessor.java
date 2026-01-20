package com.ureca.billing.core.util;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ureca.billing.core.entity.UserStatus;
import com.ureca.billing.core.entity.Users;
import com.ureca.billing.core.security.crypto.AesUtil;
import com.ureca.billing.core.security.crypto.HashUtil;

import net.datafaker.Faker;

@Component
public class UserDummyProcessor implements ItemProcessor<Long, Users> {

    private final Faker faker = new Faker(new Locale("ko"));

    private final SecretKey aesKey;
    private final String hashSecret;

    /**
     * @param aesBase64Key AES-256 Base64 인코딩 키 (crypto.aes.key)
     * @param hashSecret   HMAC-SHA256 비밀키 (crypto.hash.secret)
     */
    public UserDummyProcessor(
            @Value("${crypto.aes.key}") String aesBase64Key,
            @Value("${crypto.hash.key}") String hashSecret
    ) {
        byte[] keyBytes = Base64.getDecoder().decode(aesBase64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be 32 bytes");
        }
        this.aesKey = new SecretKeySpec(keyBytes, "AES");
        this.hashSecret = hashSecret;
    }

    @Override
    public Users process(Long seq) {

        // ===== 1️⃣ 평문 생성 =====
        String plainEmail = "user" + seq + "@test.com";
        String plainPhone = "010" + String.format("%08d", seq);

        // ===== 2️⃣ AES 암호화 (DB 저장용) =====
        String emailCipher = AesUtil.encrypt(plainEmail, aesKey);
        String phoneCipher = AesUtil.encrypt(plainPhone, aesKey);

        // ===== 3️⃣ HMAC-SHA256 해시 (검색 / UNIQUE 용) =====
        String emailHash = HashUtil.hmacSha256(plainEmail, hashSecret);
        String phoneHash = HashUtil.hmacSha256(plainPhone, hashSecret);

        // ===== 4️⃣ 기타 더미 데이터 =====
        String name = faker.name().fullName();

        LocalDate birthDate = faker.date()
                .birthday(20, 60)
                .toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDate();

        // ===== 5️⃣ 엔티티 생성 =====
        return new Users(
                emailCipher,
                emailHash,
                phoneCipher,
                phoneHash,
                name,
                birthDate,
                UserStatus.ACTIVE
        );
    }
}

