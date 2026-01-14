package com.ureca.billing.notification.controller;

import com.ureca.billing.notification.scheduler.RetryScheduler;
import com.ureca.billing.notification.scheduler.WaitingQueueScheduler;
import com.ureca.billing.notification.service.RetryService;
import com.ureca.billing.notification.service.WaitingQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Scheduler Test", description = "스케줄러 테스트 API")
@RestController
@RequestMapping("/api/test/scheduler")
@RequiredArgsConstructor
public class SchedulerTestController {
    
    private final WaitingQueueScheduler queueScheduler;
    private final RetryScheduler retryScheduler;
    private final WaitingQueueService queueService;
    private final RetryService retryService;
    
    @Operation(summary = "대기열 스케줄러 수동 실행")
    @PostMapping("/run-queue")
    public Map<String, Object> runQueueScheduler() {
        long beforeSize = queueService.getQueueSize();
        queueScheduler.processWaitingQueue();
        long afterSize = queueService.getQueueSize();
        
        return Map.of(
            "message", "Queue scheduler executed",
            "beforeQueueSize", beforeSize,
            "afterQueueSize", afterSize,
            "processed", beforeSize - afterSize
        );
    }
    
    @Operation(summary = "재시도 스케줄러 수동 실행")
    @PostMapping("/run-retry")
    public Map<String, Object> runRetryScheduler() {
        int retryCount = retryService.retryFailedMessages(100);
        
        return Map.of(
            "message", "Retry scheduler executed",
            "retriedCount", retryCount
        );
    }
    
    @Operation(summary = "대기열 상태 조회")
    @GetMapping("/queue-status")
    public Map<String, Object> getQueueStatus() {
        return Map.of(
            "queueSize", queueService.getQueueSize(),
            "status", queueService.getQueueStatus()
        );
    }
    
    @Operation(summary = "대기열 초기화")
    @DeleteMapping("/clear-queue")
    public Map<String, String> clearQueue() {
        queueService.clearQueue();
        return Map.of("message", "Queue cleared successfully");
    }
}