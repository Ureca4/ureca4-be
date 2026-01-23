package com.ureca.billing.notification.handler;

import com.ureca.billing.core.dto.BillingMessageDto;

/**
 * Notification Handler 인터페이스
 * - Strategy 패턴의 핵심
 * - EMAIL, SMS 등 다양한 알림 타입 처리
 */
public interface NotificationHandler {

    /**
     * 알림 처리
     *
     * @param message 청구 메시지
     * @param traceId 추적 ID
     */
    void handle(BillingMessageDto message, String traceId);
    
    /**
     * 알림 처리 (재시도 횟수 포함)
     * - Kafka 재시도 횟수를 전달받아 실패율 조정에 사용
     *
     * @param message 청구 메시지
     * @param traceId 추적 ID
     * @param deliveryAttempt 시도 횟수 (1=첫시도, 2이상=재시도)
     */
    default void handle(BillingMessageDto message, String traceId, int deliveryAttempt) {
        // 기본 구현: 기존 메서드 호출 (SMS, PUSH 등은 deliveryAttempt 무시)
        handle(message, traceId);
    }
    
    String getType();
}