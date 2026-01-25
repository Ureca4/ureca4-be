package com.ureca.billing.notification.scheduler;

import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.service.MessagePolicyService;
import com.ureca.billing.notification.service.ScheduledQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 예약 발송 스케줄러
 * 
 * 동작:
 * 1. 매 분마다 예약 시간이 도래한 메시지 조회
 * 2. 금지 시간대 체크 (금지 시간이면 스킵)
 * 3. Kafka로 발송 요청
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledQueueScheduler {
    
    private static final String TOPIC = "billing-event";
    private static final int BATCH_SIZE = 100;
    
    private final ScheduledQueueService scheduledQueueService;
    private final MessagePolicyService policyService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    /**
     * 매 분마다 예약 발송 처리
     */
    @Scheduled(cron = "0 * * * * *")
    public void processScheduledMessages() {
        log.debug("📅 [SCHEDULED] Checking for ready messages...");

        // 🔍 디버깅 추가
        //scheduledQueueService.debugPrintQueue("ALL");

        // 1. 금지 시간대 체크
        if (policyService.isBlockTime()) {
            log.debug("⏰ [SCHEDULED] Currently in block time. Skipping...");
            return;
        }
        
        // 2. 발송 시간 도래한 메시지 조회
        List<BillingMessageDto> readyMessages = scheduledQueueService.getReadyMessages("ALL", BATCH_SIZE);
        
        if (readyMessages.isEmpty()) {
            log.debug("📭 [SCHEDULED] No ready messages to process.");
            return;
        }
        
        log.info("📬 [SCHEDULED] Found {} messages ready to send", readyMessages.size());
        
        int successCount = 0;
        int failCount = 0;
        
        for (BillingMessageDto message : readyMessages) {
            try {
                // 3. Kafka로 발송
                String messageJson = objectMapper.writeValueAsString(message);
                kafkaTemplate.send(TOPIC, messageJson);
                
                // 4. 처리 완료 (큐에서 제거)
                String channel = message.getNotificationType() != null 
                        ? message.getNotificationType() 
                        : "EMAIL";
                scheduledQueueService.markAsProcessed(message, channel);
                
                successCount++;
                log.info("✅ [SCHEDULED] Message sent to Kafka. billId={}, userId={}", 
                        message.getBillId(), message.getUserId());
                
            } catch (Exception e) {
                failCount++;
                log.error("❌ [SCHEDULED] Failed to process. billId={}, error={}", 
                        message.getBillId(), e.getMessage());
            }
        }
        
        log.info("🎯 [SCHEDULED] Processing completed. success={}, fail={}", successCount, failCount);
    }
    
    /**
     * 통계 로깅 (매 10분)
     */
    @Scheduled(cron = "0 */10 * * * *")
    public void logStats() {
        Map<String, Long> stats = scheduledQueueService.getQueueStats();
        
        if (stats.get("ALL") > 0) {
            log.info("📊 [SCHEDULED STATS] Total={}, Ready={}, EMAIL={}, SMS={}", 
                    stats.get("ALL"), stats.get("READY"), stats.get("EMAIL"), stats.get("SMS"));
        }
    }
}