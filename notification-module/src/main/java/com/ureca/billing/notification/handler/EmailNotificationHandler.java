package com.ureca.billing.notification.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.consumer.handler.DuplicateCheckHandler;
import com.ureca.billing.notification.domain.entity.Notification;
import com.ureca.billing.notification.domain.repository.NotificationRepository;
import com.ureca.billing.notification.service.EmailService;
import com.ureca.billing.notification.service.MessagePolicyService;
import com.ureca.billing.notification.service.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Email Notification Handler
 * - 이메일 발송 처리
 * - 중복 체크, 금지 시간대 관리
 * - 첫 시도 1%, 재시도 30% 실패율 적용
 */
@Component("emailNotificationHandler")
@RequiredArgsConstructor
@Slf4j
public class EmailNotificationHandler implements NotificationHandler {

    private final EmailService emailService;

    /**
     * 기본 핸들러 (첫 시도, deliveryAttempt = 1)
     */
    @Override
    public void handle(BillingMessageDto message, String traceId) {
        // 기본값: 첫 시도 (1% 실패율)
        handle(message, traceId, 1);
    }
    
    /**
     * 재시도 횟수를 포함한 핸들러
     * - deliveryAttempt = 1: 첫 시도 (1% 실패율)
     * - deliveryAttempt >= 2: 재시도 (30% 실패율)
     */
    @Override
    public void handle(BillingMessageDto message, String traceId, int deliveryAttempt) {
        // [로그 주석 처리] 성능을 위해 INFO 로그는 끕니다.
        // log.debug("{} 📧 EMAIL 핸들러 처리 시작 - billId={}, attempt={}", traceId, message.getBillId(), deliveryAttempt);

        try {
            // deliveryAttempt를 전달하여 실패율 차등 적용
            emailService.sendEmail(message, deliveryAttempt);

            // 성공 로그도 Consumer에서 찍거나, Debug로 내림
            // log.debug("{} EMAIL 발송 성공 (attempt={})", traceId, deliveryAttempt);

        } catch (Exception e) {
            // 에러 로그는 남김 (어떤 에러인지 파악용)
            log.error("{} EMAIL 발송 실패 - attempt={}, error={}", traceId, deliveryAttempt, e.getMessage());

            // 예외를 던져야 Consumer가 이를 잡아서 "FAILED" 상태로 DB에 저장할 수 있음
            throw new RuntimeException("Email send failed", e);
        }
    }

    @Override
    public String getType() {
        return "EMAIL";
    }
}