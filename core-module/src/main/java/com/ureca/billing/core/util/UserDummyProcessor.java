package com.ureca.billing.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.ureca.billing.core.entity.NotificationType;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ureca.billing.core.entity.UserStatus;
import com.ureca.billing.core.entity.Users;

import net.datafaker.Faker;

@Component
public class UserDummyProcessor implements ItemProcessor<Long, Users> {

    private final Faker faker = new Faker(new Locale("ko"));

    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private final SecretKey aesKey;

    // AES 키를 Spring yml에서 Base64로 주입
    public UserDummyProcessor(@Value("${crypto.aes.key}") String base64Key) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("AES-256 key must be 32 bytes after Base64 decoding!");
            }
            this.aesKey = new SecretKeySpec(keyBytes, "AES");
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("AES 키가 잘못되었습니다. Base64로 인코딩된 32바이트 키여야 합니다.", e);
        }
    }

    @Override
    public Users process(Long seq) throws Exception {

        String plainEmail = "user" + seq + "@test.com";
        String plainPhone = "010" + String.format("%08d", seq);

        // 암호화
        String emailCipher = aesEncrypt(plainEmail);
        String phoneCipher = aesEncrypt(plainPhone);

        // 해시
        String emailHash = sha256(plainEmail);
        String phoneHash = sha256(plainPhone);

        String name = faker.name().fullName();

        LocalDate birthDate = faker.date()
            .birthday(20, 60)
            .toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDate();

        UserStatus status = UserStatus.ACTIVE;

        NotificationType[] types = NotificationType.values();

        return Users.builder()
                .emailCipher(emailCipher)
                .emailHash(emailHash)
                .phoneCipher(phoneCipher)
                .phoneHash(phoneHash)
                .name(name)
                .birthDate(birthDate)
                .status(status)
                .build();
    }

    // AES-256-GCM 암호화
    private String aesEncrypt(String plainText) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, spec);

        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        // IV + 암호문 합쳐서 Base64로 반환
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    // SHA-256 해시 생성
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // 필요하면 복호화도 구현 가능
    public String aesDecrypt(String cipherTextBase64) throws Exception {
        byte[] combined = Base64.getDecoder().decode(cipherTextBase64);
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];

        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, aesKey, spec);

        byte[] plainBytes = cipher.doFinal(cipherText);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }
}
