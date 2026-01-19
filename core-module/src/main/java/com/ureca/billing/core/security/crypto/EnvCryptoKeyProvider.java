package com.ureca.billing.core.security.crypto;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * EnvCryptoKeyProvider
 *
 * [역할]
 * - application.yml / 환경변수에 정의된 AES 키를 로드
 * - AES-256 SecretKey 객체로 변환하여 제공
 *
 * [설계 포인트]
 * - 키 하드코딩 금지
 * - 환경별 키 분리 가능
 * - 추후 키 로테이션 확장 가능
 */
@Component
public class EnvCryptoKeyProvider implements CryptoKeyProvider {

    @Value("${crypto.aes.key}")
    private String rawKey;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        // Base64 디코딩 (권장 방식)
        byte[] decodedKey = Base64.getDecoder().decode(rawKey);

        // AES SecretKey 생성
        this.secretKey = new SecretKeySpec(decodedKey, "AES");
    }

    @Override
    public SecretKey getCurrentKey() {
        return secretKey;
    }
}
