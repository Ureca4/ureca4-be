package com.ureca.billing.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.consumer.handler.DuplicateCheckHandler;
import com.ureca.billing.notification.domain.entity.Notification;
import com.ureca.billing.notification.domain.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 재시도 서비스
 * 
 * 아키텍처 플로우 (Retry Scheduler - 5분마다 실행):
 * 1. status = "FAILED" 조회
 * 2. retry_count < 3 인 경우:
 *    - DB 상태 업데이트: status = "RETRY", retry_count++
 *    - Redis에 재시도 정보 저장: key: retry:msg:{billId}, value: notificationId, TTL: 1시간
 *    - Kafka로 재발행 (billing-event-topic)
 *    - 처음 로직으로 돌아감
 * 3. retry_count >= 3 인 경우:
 *    - DLQ로 이동 (billing-event.DLT)
 *     - DeadLetterConsumer에서 SMS Fallback 자동 처리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetryService {
    
    private static final String TOPIC = "billing-event";
    private static final String DLT_TOPIC = "billing-event.DLT";
    private static final int MAX_RETRY_COUNT = 3;
    
    private final NotificationRepository notificationRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final DuplicateCheckHandler duplicateCheckHandler;  
    private final JdbcTemplate jdbcTemplate;
    
    
    /**
     * FAILED 메시지 재시도
     * 
     * 플로우:
     * 1. FAILED 상태 + retry_count < 3 인 메시지 조회
     * 2. 각 메시지에 대해:
     *    - DB 상태 업데이트: status = "RETRY", retry_count++
     *    - Redis에 재시도 정보 저장: retry:msg:{billId} = notificationId (TTL 1시간)
     *    - Kafka로 재발행
     * 3. retry_count >= 3 인 경우 DLQ로 이동
     */
    @Transactional
    public int retryFailedMessages(int limit) {
        log.info("🔄 [RETRY] 재시도 프로세스 시작...");
        
        // 1. FAILED 상태이면서 재시도 가능한 메시지 조회
        List<Notification> allFailedMessages = notificationRepository.findFailedMessagesForRetry();
        
        if (allFailedMessages == null || allFailedMessages.isEmpty()) {
            log.info("📭 [RETRY] 재시도할 메시지 없음");
            return 0;
        }
        
        // Java에서 limit 적용
        List<Notification> failedMessages = allFailedMessages.stream()
            .limit(limit)
            .toList();
        
        log.info("📬 [RETRY] 재시도 대상 메시지 발견. 처리 예정: {}, 전체: {}", 
                failedMessages.size(), allFailedMessages.size());
        
        int successCount = 0;
        int dlqCount = 0;
        
        for (Notification notification : failedMessages) {
            try {
                // 2. 재시도 횟수 체크
                if (notification.getRetryCount() >= MAX_RETRY_COUNT) {
                    // 3회 이상 실패 → DLQ 이동 
                    log.warn("💀 [RETRY] 최대 재시도 횟수 초과. DLT 대상. notificationId={}, retryCount={}", 
                            notification.getNotificationId(), notification.getRetryCount());
                    
                    // DLT로 메시지 전송 (SMS Fallback 처리)
                    sendToDlt(notification);
                    
                    // 최종 실패 상태로 업데이트
                    Notification finalFailure = notification.markAsFinalFailure("Max retry count exceeded → DLT");
                    notificationRepository.save(finalFailure);
                    dlqCount++;
                    continue;
                }
                
                // 3. DB 상태 업데이트: status = "RETRY", retry_count++
                Notification updatedNotification = notification.incrementRetryCount();
                notificationRepository.save(updatedNotification);
                
                log.info("📝 [RETRY] DB 상태 업데이트. notificationId={}, status=RETRY, retryCount={}", 
                        notification.getNotificationId(), updatedNotification.getRetryCount());
                
                // 4. BillingMessage 재구성
                BillingMessageDto message = reconstructMessage(notification);
                
                // 5. Redis에 재시도 정보 저장: retry:msg:{billId} = notificationId (TTL 1시간)
                duplicateCheckHandler.markAsRetry(message.getBillId(), notification.getNotificationId());
                
                log.info("💾 [RETRY] Redis에 재시도 정보 저장. billId={}, notificationId={}", 
                        message.getBillId(), notification.getNotificationId());
                
                // 6. Kafka로 재발행
                String messageJson = objectMapper.writeValueAsString(message);
                kafkaTemplate.send(TOPIC, messageJson);
                
                log.info("📤 [RETRY] Kafka로 재발행 완료. billId={}, notificationId={}, retryCount={}", 
                        message.getBillId(), notification.getNotificationId(), updatedNotification.getRetryCount());
                
                successCount++;
                
            } catch (Exception e) {
                log.error("❌ [RETRY] 재시도 처리 실패. notificationId={}, error={}", 
                        notification.getNotificationId(), e.getMessage());
                
            }
        }
        
        log.info("🎯 [RETRY] 재시도 프로세스 완료. 성공: {}, DLT전송: {}, 총 처리: {}", 
                successCount, dlqCount, successCount + dlqCount);
        
        return successCount;
    }
    
   /*
    * 3회 실패 메시지를 DLT로 전송하는 메서드 (SMS Fallback 처리용)
    * 
    * 아키텍처:
    * retry_count >= 3 → DLT 토픽 → DeadLetterConsumer → SMS 자동 발송
    */
   private void sendToDlt(Notification notification) {
       try {
           // BillingMessageDto 재구성
           BillingMessageDto message = reconstructMessageForDlt(notification);
           
           // DLT 토픽으로 전송
           String messageJson = objectMapper.writeValueAsString(message);
           kafkaTemplate.send(DLT_TOPIC, messageJson);
           
           log.info("📤 [DLT] DLT 토픽으로 전송 완료. billId={}, notificationId={}", 
                   notification.getBillId(), notification.getNotificationId());
           
       } catch (Exception e) {
           log.error("❌ [DLT] DLT 전송 실패. notificationId={}, error={}", 
                   notification.getNotificationId(), e.getMessage());
       }
   }
   
   /**
    * DLT용 BillingMessageDto 재구성
    * SMS 발송에 필요한 정보 포함
    */
   private BillingMessageDto reconstructMessageForDlt(Notification notification) {
	   
       // 1. bills 테이블에서 청구 정보 조회
       Map<String, Object> billInfo = getBillInfo(notification.getBillId());
       
       // 2. users 테이블에서 전화번호 조회
       String phoneCipher = getPhoneCipherByUserId(notification.getUserId());
       
       return BillingMessageDto.builder()
           .billId(notification.getBillId())
           .userId(notification.getUserId())
           .recipientEmail(notification.getRecipient())  // EMAIL 수신자
           .recipientPhone(phoneCipher)  // ✅ 실제 전화번호 (암호화된 상태)
           .totalAmount((Long) billInfo.get("totalAmount"))
           .billYearMonth((String) billInfo.get("billingMonth"))
           .billDate((String) billInfo.get("billDate"))
           .dueDate((String) billInfo.get("dueDate"))
           .notificationType("EMAIL")  // 원래 타입
           .build();
   }
   
   /**
    * userId로 전화번호 조회
    */
   private String getPhoneByUserId(Long userId) {
       // 임시: 마스킹된 전화번호 반환
       // 실제로는 userRepository.findById(userId).getPhoneCipher() 등으로 조회
       return "010-1234-5678";
   }


    /**
     * Notification에서 BillingMessageDto 재구성 (재시도용)
     */
    private BillingMessageDto reconstructMessage(Notification notification) {
    	
        // bills 테이블에서 청구 정보 조회
        Map<String, Object> billInfo = getBillInfo(notification.getBillId());
        
        return BillingMessageDto.builder()
        	.billId(notification.getBillId())  // ✅ notificationId 대신 billId 사용
            .userId(notification.getUserId())
            .recipientEmail(notification.getRecipient())
            .recipientPhone(null) // EMAIL 재시도에는 전화번호 불필요
            .totalAmount((Long) billInfo.get("totalAmount"))
            .billYearMonth((String) billInfo.get("billingMonth"))
            .billDate((String) billInfo.get("billDate"))
            .dueDate((String) billInfo.get("dueDate"))
            .build();
    }
    
    /**
      * ✅ bills 테이블에서 청구 정보 조회
     */
    private Map<String, Object> getBillInfo(Long billId) {
        try {
            // bill_details에서 총 금액 계산
            Long totalAmount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM bill_details WHERE bill_id = ?",
                Long.class,
                billId
            );
            
            // bills 테이블에서 날짜 정보 조회
            Map<String, Object> billData = jdbcTemplate.queryForMap(
                """
                SELECT billing_month, 
                       DATE_FORMAT(bill_issue_date, '%Y-%m-%d') as bill_date,
                       DATE_FORMAT(DATE_ADD(bill_issue_date, INTERVAL 15 DAY), '%Y-%m-%d') as due_date
                FROM bills 
                WHERE bill_id = ?
                """,
                billId
            );
            
            return Map.of(
                "totalAmount", totalAmount != null ? totalAmount : 0L,
                "billingMonth", billData.get("billing_month") != null ? billData.get("billing_month").toString() : "N/A",
                "billDate", billData.get("bill_date") != null ? billData.get("bill_date").toString() : "N/A",
                "dueDate", billData.get("due_date") != null ? billData.get("due_date").toString() : "N/A"
            );
            
        } catch (Exception e) {
            log.warn("⚠️ [DB] bills 조회 실패. billId={}, error={}", billId, e.getMessage());
            // 기본값 반환
            return Map.of(
                "totalAmount", 0L,
                "billingMonth", "N/A",
                "billDate", "N/A",
                "dueDate", "N/A"
            );
        }
    }
    
    /**
     * ✅ users 테이블에서 전화번호 조회 (암호화된 상태)
     */
    private String getPhoneCipherByUserId(Long userId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT phone_cipher FROM users WHERE user_id = ?",
                String.class,
                userId
            );
        } catch (Exception e) {
            log.warn("⚠️ [DB] users 전화번호 조회 실패. userId={}, error={}", userId, e.getMessage());
            return null;
        }
    }
    
    /**
     * 기존 FAILED 메시지 DLT 일괄 전송
     */
    @Transactional
    public int sendExistingFailedToDlt(int limit) {
        log.info("🚀 [DLT BATCH] 기존 FAILED 메시지 DLT 일괄 전송 시작...");
        
        // retry_count >= 3인 FAILED 메시지 조회
        List<Notification> maxRetryFailedMessages = notificationRepository.findMaxRetryFailedMessages();
        
        if (maxRetryFailedMessages == null || maxRetryFailedMessages.isEmpty()) {
            log.info("📭 [DLT BATCH] DLT 전송 대상 메시지 없음");
            return 0;
        }
        
        List<Notification> targetMessages = maxRetryFailedMessages.stream()
            .limit(limit)
            .toList();
        
        log.info("📬 [DLT BATCH] DLT 전송 대상: {}, 전체: {}", 
                targetMessages.size(), maxRetryFailedMessages.size());
        
        int successCount = 0;
        
        for (Notification notification : targetMessages) {
            try {
                // DLT로 전송
                sendToDlt(notification);
                
                // 상태 업데이트 (DLT 전송됨 표시)
                Notification updated = notification.markAsFinalFailure("Sent to DLT for SMS Fallback");
                notificationRepository.save(updated);
                
                successCount++;
                
            } catch (Exception e) {
                log.error("❌ [DLT BATCH] DLT 전송 실패. notificationId={}", 
                        notification.getNotificationId());
            }
        }
        
        log.info("🎯 [DLT BATCH] DLT 일괄 전송 완료. 성공: {}", successCount);
        return successCount;
    }
    
    /**
     * 특정 Notification 수동 재시도
     */
    @Transactional
    public boolean retryNotification(Long notificationId) {
        log.info("🔄 [MANUAL RETRY] 수동 재시도 요청. notificationId={}", notificationId);
        
        return notificationRepository.findById(notificationId)
            .map(notification -> {
                if (notification.getRetryCount() >= MAX_RETRY_COUNT) {
                    log.warn("⚠️ [MANUAL RETRY] 최대 재시도 횟수 초과. notificationId={}", notificationId);
                    return false;
                }
                
                try {
                    // DB 상태 업데이트
                    Notification updated = notification.incrementRetryCount();
                    notificationRepository.save(updated);
                    
                    // BillingMessage 재구성
                    BillingMessageDto message = reconstructMessage(notification);
                    
                    // Redis에 재시도 정보 저장
                    duplicateCheckHandler.markAsRetry(message.getBillId(), notificationId);
                    
                    // Kafka로 재발행
                    String messageJson = objectMapper.writeValueAsString(message);
                    kafkaTemplate.send(TOPIC, messageJson);
                    
                    log.info("✅ [MANUAL RETRY] 수동 재시도 완료. notificationId={}", notificationId);
                    return true;
                    
                } catch (Exception e) {
                    log.error("❌ [MANUAL RETRY] 수동 재시도 실패. notificationId={}, error={}", 
                            notificationId, e.getMessage());
                    return false;
                }
            })
            .orElseGet(() -> {
                log.warn("⚠️ [MANUAL RETRY] Notification을 찾을 수 없음. notificationId={}", notificationId);
                return false;
            });
    }
}