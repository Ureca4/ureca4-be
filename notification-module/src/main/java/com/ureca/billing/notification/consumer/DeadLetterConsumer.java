package com.ureca.billing.notification.consumer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.consumer.handler.DuplicateCheckHandler;
import com.ureca.billing.notification.domain.entity.Notification;
import com.ureca.billing.notification.domain.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dead Letter Topic Consumer
 * 
 * 3회 재시도 실패한 EMAIL 메시지를 받아서 SMS로 자동 폴백 발송
 * 
 * 배치 모드 지원 (KafkaConsumerConfig와 일치)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterConsumer {

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;
    private final DuplicateCheckHandler duplicateCheckHandler;

    /**
     * DLT 메시지 배치 처리
     * - 배치로 들어온 메시지들을 순차 처리
     * - 각 메시지마다 SMS 폴백 수행
     */
    @KafkaListener(
        topics = "billing-event-dlt",
        groupId = "dlq-group",
        concurrency = "3",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void listenDeadLetter(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        log.warn("🚨 [DLT] {}개 메시지 수신", records.size());
        
        List<Notification> notificationsToSave = new ArrayList<>();
        
        for (ConsumerRecord<String, String> record : records) {
            String traceInfo = String.format("[DLT-P%d-O%d]", record.partition(), record.offset());
            
            try {
                processSingleDltMessage(record, traceInfo, notificationsToSave);
            } catch (Exception e) {
                log.error("{} ❌ DLT 메시지 처리 실패: {}", traceInfo, e.getMessage());
            }
        }
        
        // 일괄 저장
        if (!notificationsToSave.isEmpty()) {
            notificationRepository.saveAll(notificationsToSave);
            log.info("🚨 [DLT] {}개 Notification 저장 완료", notificationsToSave.size());
        }
        
        // 배치 커밋
        ack.acknowledge();
        log.info("🚨 [DLT] {}개 처리 완료", records.size());
    }

    /**
     * 단건 DLT 메시지 처리
     */
    private void processSingleDltMessage(ConsumerRecord<String, String> record, 
                                          String traceInfo, 
                                          List<Notification> notificationsToSave) {
        try {
            String messageJson = record.value();

            // 빈 메시지 체크
            if (messageJson == null || messageJson.trim().isEmpty()) {
                log.warn("{} ⚠️ 빈 메시지 스킵", traceInfo);
                return;
            }

            // 이중 직렬화 처리
            if (messageJson.startsWith("\"") && messageJson.endsWith("\"")) {
                messageJson = objectMapper.readValue(messageJson, String.class);
            }

            BillingMessageDto message = objectMapper.readValue(messageJson, BillingMessageDto.class);

            log.info("{} 📱 EMAIL 3회 실패 → SMS 자동 Fallback 시작. billId={}", 
                    traceInfo, message.getBillId());

            // 1️⃣ 기존 EMAIL FAILED 레코드 업데이트
            updateEmailFailedRecord(message, traceInfo);

            // 2️⃣ SMS 자동 발송 (항상 성공 - 에러처리 X)
            Notification smsNotification = sendSmsFallback(message, traceInfo);
            if (smsNotification != null) {
                notificationsToSave.add(smsNotification);
            }

            log.info("{} ✅ DLT 처리 완료. EMAIL FAILED 업데이트 + SMS 발송 완료. billId={}", 
                    traceInfo, message.getBillId());

        } catch (Exception e) {
            log.error("{} ❌ DLT 처리 실패: {}", traceInfo, e.getMessage());
        }
    }

    /**
     * 1️⃣ 기존 EMAIL FAILED 레코드 업데이트
     * - errorMessage를 "DLT → SMS Fallback으로 대체 발송"으로 변경
     */
    private void updateEmailFailedRecord(BillingMessageDto message, String traceInfo) {
        try {
            // billId로 기존 EMAIL FAILED 레코드 조회
            Optional<Notification> existingOpt = notificationRepository
                    .findByBillIdAndType(message.getBillId(), "EMAIL");
            
            if (existingOpt.isPresent()) {
                Notification existing = existingOpt.get();
                
                // 업데이트된 레코드 생성 (불변 객체이므로 새로 빌드)
                Notification updated = Notification.builder()
                    .notificationId(existing.getNotificationId())
                    .userId(existing.getUserId())
                    .notificationType(existing.getNotificationType())
                    .notificationStatus("FAILED")  // 상태 유지
                    .billId(existing.getBillId())
                    .recipient(existing.getRecipient())
                    .content(existing.getContent())
                    .retryCount(existing.getRetryCount())
                    .scheduledAt(existing.getScheduledAt())
                    .sentAt(existing.getSentAt())
                    .errorMessage("🚨 DLT → SMS Fallback으로 대체 발송")  // ✅ 메시지 업데이트
                    .createdAt(existing.getCreatedAt())
                    .build();
                
                notificationRepository.save(updated);
                log.info("{} 💾 EMAIL FAILED 레코드 업데이트 완료. notificationId={}", 
                        traceInfo, existing.getNotificationId());
            } else {
                // 기존 레코드가 없으면 새로 생성
                log.warn("{} ⚠️ 기존 EMAIL 레코드 없음. 새로 FAILED 레코드 생성", traceInfo);
                saveEmailFailedRecord(message);
            }
        } catch (Exception e) {
            log.error("{} ❌ EMAIL FAILED 업데이트 실패: {}", traceInfo, e.getMessage());
            // 실패해도 SMS 발송은 계속 진행
        }
    }

    /**
     * EMAIL FAILED 레코드 새로 생성 (기존 레코드가 없는 경우)
     */
    private void saveEmailFailedRecord(BillingMessageDto message) {
        String content = String.format(
            "[LG U+ 청구 알림 - EMAIL 최종 실패]\n청구 년월: %s\n총 청구 금액: %,d원",
            message.getBillYearMonth(),
            message.getTotalAmount() != null ? message.getTotalAmount() : 0
        );

        Notification notification = Notification.builder()
            .userId(message.getUserId())
            .notificationType("EMAIL")
            .notificationStatus("FAILED")
            .billId(message.getBillId())
            .recipient(message.getRecipientEmail())
            .content(content)
            .retryCount(3)
            .errorMessage("🚨 DLT → SMS Fallback으로 대체 발송")
            .createdAt(LocalDateTime.now())
            .build();

        notificationRepository.save(notification);
    }

    /**
     * 2️⃣ SMS 자동 Fallback 발송
     * - 실패 처리 안함 (요구사항: SMS는 실패처리하지 않아도 됨)
     * - 개발자 개입 X (완전 자동화)
     */
    private Notification sendSmsFallback(BillingMessageDto message, String traceInfo) {
    	// 중복 체크 
    	if (duplicateCheckHandler.isDuplicate(message.getBillId(), "SMS")) {
            log.warn("{} ⚠️ SMS 이미 발송됨. 중복 스킵. billId={}", traceInfo, message.getBillId());
            return null;
        }
    	
        // SMS 발송 시뮬레이션 (Mocking - 항상 성공)
        log.info("{} 📱 [SMS 발송] to: {}, billId: {}, amount: {}원", 
            traceInfo,
            maskPhone(message.getRecipientPhone()),
            message.getBillId(),
            message.getTotalAmount() != null ? String.format("%,d", message.getTotalAmount()) : "0"
        );

        // Redis에 SMS 발송 완료 마킹
        duplicateCheckHandler.markAsSent(message.getBillId(), "SMS");

        // 3️⃣ SMS SENT 레코드 생성 및 반환
        return createSmsNotification(message);
    }

    /**
     * SMS SENT 레코드 생성
     */
    private Notification createSmsNotification(BillingMessageDto message) {
        String content = String.format(
            "[LG U+] %s 청구액 %,d원. 납부기한: %s (EMAIL 실패 → SMS 자동발송)",
            message.getBillYearMonth(),
            message.getTotalAmount() != null ? message.getTotalAmount() : 0,
            message.getDueDate() != null ? message.getDueDate() : "미정"
        );

        return Notification.builder()
            .userId(message.getUserId())
            .notificationType("SMS")
            .notificationStatus("SENT")
            .billId(message.getBillId())
            .recipient(message.getRecipientPhone())
            .content(content)
            .retryCount(0)
            .scheduledAt(LocalDateTime.now())
            .sentAt(LocalDateTime.now())
            .errorMessage(null)
            .createdAt(LocalDateTime.now())
            .build();
    }

    /**
     * 전화번호 마스킹 (개인정보 보호)
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}