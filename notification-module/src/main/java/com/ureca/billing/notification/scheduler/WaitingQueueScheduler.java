package com.ureca.billing.notification.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.notification.service.MessagePolicyService;
import com.ureca.billing.notification.service.WaitingQueueService;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class WaitingQueueScheduler {
    
    private static final String TOPIC = "billing-event";
    
    private final WaitingQueueService queueService;
    private final MessagePolicyService policyService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    private volatile boolean isShuttingDown = false; 
    
    @PreDestroy  
    public void onShutdown() {
        isShuttingDown = true;
        log.info("🛑 Scheduler shutting down...");
    }
    
    /**
     * 매일 08:00에 대기열 메시지 재발송
     */
    @Scheduled(cron = "0 0 8 * * *")  // 매일 08:00
    public void processWaitingQueue() {
        log.info("⏰ [SCHEDULER] Starting to process waiting queue at 08:00...");
        
        // 1. 현재 금지 시간인지 체크
        if (policyService.isBlockTime()) {
            log.warn("⚠️ [SCHEDULER] Still in block time. Skipping...");
            return;
        }
        
        // 2. 대기열 크기 확인
        long queueSize = queueService.getQueueSize();
        if (queueSize == 0) {
            log.info("📭 [SCHEDULER] Waiting queue is empty. Nothing to process.");
            return;
        }
        
        log.info("📬 [SCHEDULER] Found {} messages in waiting queue", queueSize);
        
        // 3. 발송 가능한 메시지 조회 (최대 1000개)
        Set<String> messages = queueService.getReadyMessages(1000);
        
        if (messages == null || messages.isEmpty()) {
            log.info("📭 [SCHEDULER] No ready messages to process.");
            return;
        }
        
        int successCount = 0;
        int failCount = 0;
        
        // 4. 각 메시지를 Kafka로 재발행
        for (String messageJson : messages) {
            try {
                // JSON → DTO 변환 (유효성 검사)
            	BillingMessageDto message = objectMapper.readValue(messageJson, BillingMessageDto.class);
                
                // Kafka로 재발행
                kafkaTemplate.send(TOPIC, messageJson);
                
                // 대기열에서 제거
                queueService.removeFromQueue(messageJson);
                
                successCount++;
                log.info("✅ [SCHEDULER] Re-published message. billId={}", message.getBillId());
                
            } catch (Exception e) {
                failCount++;
                log.error("❌ [SCHEDULER] Failed to re-publish message: {}", e.getMessage());
            }
        }
        
        log.info("🎯 [SCHEDULER] Completed. success={}, fail={}, remaining={}", 
            successCount, failCount, queueService.getQueueSize());
    }
    
    /**
     * 테스트용: 매 1분마다 실행
     * 개발 중에만 사용하고, 운영 시에는 주석 처리
     */
    @Scheduled(cron = "0 * * * * *")  // 매 1분
    public void processWaitingQueueEveryMinute() {
        log.info("🧪 [TEST SCHEDULER] Running test scheduler every minute...");
        
        // 금지 시간이면 스킵
        if (policyService.isBlockTime()) {
            log.info("⏰ [TEST SCHEDULER] Currently in block time. Skipping...");
            return;
        }
        
        // 대기열에 메시지가 있으면 처리
        long queueSize = queueService.getQueueSize();
        if (queueSize > 0) {
            log.info("📬 [TEST SCHEDULER] Processing {} messages...", queueSize);
            processWaitingQueue();
        } else {
            log.debug("📭 [TEST SCHEDULER] Queue is empty. Nothing to do.");
        }
    }
}