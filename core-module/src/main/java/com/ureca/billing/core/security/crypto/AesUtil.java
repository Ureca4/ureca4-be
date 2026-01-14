package com.ureca.billing.core.security.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AesUtil
 *
 * [역할]
 * - 개인정보(DB 컬럼)를 AES-256-GCM 방식으로 암·복호화하는 공통 유틸 클래스
 *
 * [설계 의도]
 * - 비즈니스 로직(Service, Controller)이 암호화를 직접 다루지 않도록 분리
 * - JPA AttributeConverter에서만 사용되도록 설계
 * - 모든 모듈(User, Batch, Notification)에서 동일한 암호화 정책 유지
 *
 * [보안 설계 포인트]
 * - AES-256-GCM (기밀성 + 무결성 제공하는 AEAD 방식)
 * - 암호화 시마다 랜덤 IV 생성 (IV 재사용 방지)
 * - 위변조 발생 시 복호화 단계에서 즉시 예외 발생
 * - IV + 암호문(+Auth Tag)을 함께 저장
 * - 결과는 Base64 문자열로 변환하여 DB 저장 가능
 */
public class AesUtil {

    /**
     * AES-GCM 암호화 변환 방식
     * - NoPadding: GCM 모드는 패딩 불필요
     */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /**
     * GCM 권장 IV 길이 = 12바이트
     */
    private static final int IV_LENGTH = 12;

    /**
     * 인증 태그 길이 (bits 단위)
     * - 128bit = 16바이트 (권장)
     */
    private static final int TAG_LENGTH_BIT = 128;

    /**
     * 유틸 클래스이므로 인스턴스 생성 방지
     */
    private AesUtil() {
    }

    /**
     * 평문 문자열을 AES-256-GCM 방식으로 암호화한다.
     *
     * @param plainText 암호화할 평문 (예: email, phone)
     * @param secretKey 환경변수에서 로드된 AES 비밀키
     * @return Base64로 인코딩된 암호문 (IV + CipherText + AuthTag)
     */
    public static String encrypt(String plainText, SecretKey secretKey) {
        try {
            // 1️⃣ 매 암호화마다 새로운 IV 생성 (GCM에서 매우 중요)
            byte[] iv = generateIv();

            // 2️⃣ GCM 파라미터 설정 (IV + 인증 태그 길이)
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);

            // 3️⃣ Cipher 생성 및 초기화
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    secretKey,
                    gcmSpec
            );

            // 4️⃣ 평문 → 암호문 (+ AuthTag 포함)
            byte[] encrypted = cipher.doFinal(plainText.getBytes());

            /*
             * 5️⃣ IV + 암호문 결합
             * - GCM에서는 암호문 끝에 AuthTag가 자동 포함됨
             * - [IV][CipherText + AuthTag] 구조
             */
            byte[] ivAndCipherText = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, ivAndCipherText, 0, iv.length);
            System.arraycopy(encrypted, 0, ivAndCipherText, iv.length, encrypted.length);

            // 6️⃣ DB 저장을 위해 Base64 문자열로 인코딩
            return Base64.getEncoder().encodeToString(ivAndCipherText);

        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * AES-256-GCM으로 암호화된 문자열을 복호화한다.
     *
     * @param cipherText DB에 저장된 Base64 암호문
     * @param secretKey 환경변수에서 로드된 AES 비밀키
     * @return 복호화된 평문 문자열
     *
     * @throws IllegalStateException
     *         - 암호문이 위변조된 경우 (AuthTag 검증 실패)
     */
    public static String decrypt(String cipherText, SecretKey secretKey) {
        try {
            // 1️⃣ Base64 디코딩
            byte[] decoded = Base64.getDecoder().decode(cipherText);

            /*
             * 2️⃣ IV와 암호문(+AuthTag) 분리
             * - 앞 12바이트: IV
             * - 나머지: CipherText + AuthTag
             */
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[decoded.length - IV_LENGTH];

            System.arraycopy(decoded, 0, iv, 0, IV_LENGTH);
            System.arraycopy(decoded, IV_LENGTH, encrypted, 0, encrypted.length);

            // 3️⃣ GCM 파라미터 설정
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);

            // 4️⃣ Cipher 초기화 (복호화 모드)
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    gcmSpec
            );

            // 5️⃣ 복호화 수행
            // - AuthTag 검증 실패 시 예외 발생 (위변조 탐지)
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted);

        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }

    /**
     * 안전한 랜덤 IV 생성
     *
     * - SecureRandom 사용
     * - GCM 모드에서 IV 재사용은 치명적이므로
     *   매 암호화마다 반드시 새로 생성
     */
    private static byte[] generateIv() {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return iv;
    }
}
