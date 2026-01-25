package com.ureca.billing.batch.scheduler;

import java.time.LocalDateTime;
import java.time.YearMonth;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;

@Component
@RequiredArgsConstructor
@Slf4j
public class BatchScheduler {
 
 private final JobLauncher jobLauncher;
 private final Job monthlyBillingJob;
 
 /**
  * 매월 말일 23:00에 자동 실행
  * - 해당 월의 청구서 생성
  */
 @Scheduled(cron = "0 0 23 L * *")  // 매월 마지막 날 23:00
 public void runMonthlyBilling() {
     YearMonth currentMonth = YearMonth.now();
     log.info("🚀 [AUTO BATCH] Starting monthly billing for {}", currentMonth);
     
     try {
         JobParameters params = new JobParametersBuilder()
             .addString("billingMonth", currentMonth.toString())
             .addString("runTime", LocalDateTime.now().toString())
             .toJobParameters();
         
         JobExecution execution = jobLauncher.run(monthlyBillingJob, params);
         log.info("✅ [AUTO BATCH] Completed. status={}", execution.getStatus());
         
     } catch (Exception e) {
         log.error("❌ [AUTO BATCH] Failed", e);
     }
 }
}
