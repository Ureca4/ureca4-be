package com.ureca.billing.core.security.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * HashUtil
 *
 * [역할]
 * - 개인정보 검색/UNIQUE 처리를 위한 단방향 해시 유틸
 *
 * [설계 의도]
 * - 암호화(AES)와 검색(SHA-256)의 책임 분리
 * - DB 검색 및 인덱스 최적화를 위해 고정 길이 해시 사용
 *
 * [보안 포인트]
 * - SHA-256 단방향 해시
 * - 복호화 불가
 * - 평문 노출 방지
 */
public final class HashUtil {

    private HashUtil() {
        // util class
    }

    /**
     * SHA-256 해시 생성
     *
     * @param value 해시 대상 값 (email, phone)
     * @return 64자리 hex 문자열
     */
    public static String sha256(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Hash value must not be null or blank");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));

            // byte[] → hex string (64 chars)
            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException e) {
            // JVM 환경에서 SHA-256은 반드시 존재
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
