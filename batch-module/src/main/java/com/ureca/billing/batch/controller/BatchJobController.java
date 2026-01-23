package com.ureca.billing.batch.controller;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
public class BatchJobController {

    private final JobLauncher jobLauncher;
    private final Job monthlyBillingJob;

    /**
     * 월별 요금 정산 Job 실행 API
     * @param billingMonth 정산 대상 월 (형식: yyyy-MM, 예: 2025-01)
     */
    @PostMapping("/monthly-billing")
    public ResponseEntity<BatchJobResponse> runMonthlyBillingJob(
            @RequestParam("billingMonth") String billingMonth) {
        try {
            YearMonth targetMonth;
            try {
                targetMonth = YearMonth.parse(billingMonth);
            } catch (DateTimeParseException e) {
                return ResponseEntity.badRequest()
                        .body(new BatchJobResponse(null, "FAILED", 
                                "잘못된 월 형식입니다. yyyy-MM 형식으로 입력해주세요. (예: 2025-01)"));
            }

            JobParameters jobParameters = new JobParametersBuilder()
                    .addString("billingMonth", targetMonth.toString())
                    .addString("runTime", LocalDateTime.now().toString())
                    .toJobParameters();

            JobExecution jobExecution = jobLauncher.run(monthlyBillingJob, jobParameters);

            log.info("Monthly Billing Job started. JobExecutionId: {}, Status: {}, BillingMonth: {}",
                    jobExecution.getId(), jobExecution.getStatus(), targetMonth);

            return ResponseEntity.ok(new BatchJobResponse(
                    jobExecution.getId(),
                    jobExecution.getStatus().toString(),
                    String.format("%s 월 요금 정산 Job이 시작되었습니다.", targetMonth)
            ));
        } catch (Exception e) {
            log.error("Monthly Billing Job 실행 실패", e);
            return ResponseEntity.internalServerError()
                    .body(new BatchJobResponse(null, "FAILED", "Job 실행 실패: " + e.getMessage()));
        }
    }

    public record BatchJobResponse(Long jobExecutionId, String status, String message) {}
}
