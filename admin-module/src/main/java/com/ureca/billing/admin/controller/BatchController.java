package com.ureca.billing.admin.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

@Tag(name = "Admin - 배치 관리", description = "배치 작업 실행 API (batch-module 프록시)")
@RestController
@RequestMapping("/api/admin/batch")
@Slf4j
public class BatchController {

    private final WebClient batchWebClient;

    public BatchController(@Qualifier("batchWebClient") WebClient batchWebClient) {
        this.batchWebClient = batchWebClient;
    }

    @Operation(summary = "월별 요금 정산 배치 실행", 
               description = "batch-module의 배치 작업을 프록시하여 실행")
    @PostMapping("/monthly-billing")
    public ResponseEntity<Map<String, Object>> runMonthlyBilling(
            @Parameter(description = "정산 대상 월 (형식: yyyy-MM, 예: 2025-01)")
            @RequestParam("billingMonth") String billingMonth) {
        
        try {
            // batch-module API 호출
            Map<String, Object> result = batchWebClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/batch/monthly-billing")
                            .queryParam("billingMonth", billingMonth)
                            .build())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), 
                        response -> {
                            log.error("❌ batch-module API 에러 응답. status={}, billingMonth={}", 
                                response.statusCode(), billingMonth);
                            return response.bodyToMono(String.class)
                                .map(body -> new RuntimeException(
                                    String.format("batch-module API 에러 (status: %s, body: %s)", 
                                        response.statusCode(), body)));
                        })
                    .bodyToMono(Map.class)
                    .block();
            
            log.info("✅ 배치 작업 실행 완료. billingMonth={}, result={}", billingMonth, result);
            
            // batch-module 응답 구조: {jobExecutionId, status, message}
            // 프론트엔드가 기대하는 구조로 변환
            if (result != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("jobExecutionId", result.get("jobExecutionId"));
                response.put("status", result.get("status") != null ? result.get("status") : "UNKNOWN");
                response.put("message", result.get("message") != null ? result.get("message") : "배치 작업이 시작되었습니다.");
                return ResponseEntity.ok(response);
            } else {
                Map<String, Object> response = new HashMap<>();
                response.put("jobExecutionId", null);
                response.put("status", "UNKNOWN");
                response.put("message", "배치 작업이 시작되었습니다.");
                return ResponseEntity.ok(response);
            }
            
        } catch (Exception e) {
            log.error("❌ 배치 작업 실행 실패. billingMonth={}, error={}", billingMonth, e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("jobExecutionId", null);
            errorResponse.put("status", "FAILED");
            String errorMessage = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            errorResponse.put("message", "배치 실행 실패: " + (errorMessage != null ? errorMessage : "알 수 없는 오류"));
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}
