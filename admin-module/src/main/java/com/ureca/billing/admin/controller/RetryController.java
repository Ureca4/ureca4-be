package com.ureca.billing.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Tag(name = "Admin - 재시도 관리", description = "재시도 및 DLT 전송 API (notification-module 프록시)")
@RestController
@RequestMapping("/api/admin/retry")
@Slf4j
public class RetryController {

    private final WebClient notificationWebClient;

    public RetryController(@Qualifier("notificationWebClient") WebClient notificationWebClient) {
        this.notificationWebClient = notificationWebClient;
    }

    @Operation(summary = "재시도 실행", 
               description = "notification-module의 재시도 작업을 프록시하여 실행")
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runRetry(
            @Parameter(description = "최대 처리 개수")
            @RequestParam(name = "maxCount", defaultValue = "100") int maxCount) {
        
        try {
            Map<String, Object> result = notificationWebClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/retry/run")
                            .queryParam("maxCount", maxCount)
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            
            log.info("✅ 재시도 실행 완료. maxCount={}, result={}", maxCount, result);
            
            return ResponseEntity.ok(result != null ? result : Map.of(
                "success", true,
                "message", "재시도가 실행되었습니다."
            ));
            
        } catch (Exception e) {
            log.error("❌ 재시도 실행 실패. maxCount={}, error={}", maxCount, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                        "success", false,
                        "message", "재시도 실행 실패: " + e.getMessage()
                    ));
        }
    }

    @Operation(summary = "DLT 전송 (SMS Fallback)", 
               description = "notification-module의 DLT 전송 작업을 프록시하여 실행")
    @PostMapping("/send-to-dlt")
    public ResponseEntity<Map<String, Object>> sendToDlt(
            @Parameter(description = "처리할 최대 개수")
            @RequestParam(name = "maxCount", defaultValue = "100") int maxCount) {
        
        try {
            Map<String, Object> result = notificationWebClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/retry/send-to-dlt")
                            .queryParam("maxCount", maxCount)
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            
            log.info("✅ DLT 전송 완료. maxCount={}, result={}", maxCount, result);
            
            return ResponseEntity.ok(result != null ? result : Map.of(
                "success", true,
                "message", "DLT 전송이 완료되었습니다."
            ));
            
        } catch (Exception e) {
            log.error("❌ DLT 전송 실패. maxCount={}, error={}", maxCount, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                        "success", false,
                        "message", "DLT 전송 실패: " + e.getMessage()
                    ));
        }
    }
}
