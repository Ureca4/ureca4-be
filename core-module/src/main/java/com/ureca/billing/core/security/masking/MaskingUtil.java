package com.ureca.billing.core.security.masking;

/**
 * MaskingUtil
 *
 * - 로그 출력 시 개인정보 마스킹 처리
 * - 평문 노출 방지 목적
 */
public final class MaskingUtil {

    private MaskingUtil() {
    }

    /**
     * 이메일 마스킹
     * ex) testuser@gmail.com -> te****er@gmail.com
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }

        String[] parts = email.split("@");
        String local = parts[0];
        String domain = parts[1];

        if (local.length() <= 2) {
            return "***@" + domain;
        }

        return local.substring(0, 2)
                + "****"
                + local.substring(local.length() - 2)
                + "@"
                + domain;
    }

    /**
     * 전화번호 마스킹
     * ex) 01012345678 -> 010****5678
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }

        return phone.substring(0, 3)
                + "****"
                + phone.substring(phone.length() - 4);
    }
}
