package com.ureca.billing.notification.scheduler;

import com.ureca.billing.notification.service.RetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RetryScheduler {
    
    private final RetryService retryService;
    
    /**
     * 매 5분마다 FAILED 메시지 재시도
     */
    @Scheduled(cron = "0 */5 * * * *")  // 매 5분마다
    public void retryFailedMessages() {
        log.info("🔄 [RETRY SCHEDULER] Starting retry process...");
        
        int retryCount = retryService.retryFailedMessages(100);  // 최대 100개
        
        log.info("🎯 [RETRY SCHEDULER] Completed. retried={}", retryCount);
    }
    
    /**
     * 테스트용: 매 1분마다 실행
     */
    @Scheduled(cron = "0 * * * * *")  // 매 1분
    public void retryFailedMessagesEveryMinute() {
        log.info("🧪 [TEST RETRY SCHEDULER] Running test retry scheduler...");
        
        int retryCount = retryService.retryFailedMessages(10);  // 최대 10개
        
        if (retryCount > 0) {
            log.info("🎯 [TEST RETRY SCHEDULER] Retried {} messages", retryCount);
        }
    }
}