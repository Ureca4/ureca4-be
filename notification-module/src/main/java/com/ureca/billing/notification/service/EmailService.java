package com.ureca.billing.notification.service;

import com.ureca.billing.core.dto.BillingMessageDto;  // ✅ core-module의 DTO 사용
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Random;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmailService {
    
    private final Random random = new Random();
    private final JavaMailSender mailSender;
    
    @Value("${notification.email.enabled:false}")
    private boolean realEmailEnabled;
    
    @Value("${notification.email.test-recipient:quokka3306@gmail.com}")
    private String testRecipient;
    
    @Value("${notification.email.initial-failure-rate:1}")
    private int initialFailureRate;  // 첫 시도 실패율 (기본 1%)
    
    @Value("${notification.email.retry-failure-rate:30}")
    private int retryFailureRate;    // 재시도 실패율 (기본 30%)
    
    /**
     * 이메일 발송
     * - 1초 지연
     * - 첫시도: 1% 확률로 실패
     * - 재시도: 30% 확률로 실패
     * 
     * @param message 발송 메시지
     * @param deliveryAttempt 시도 횟수 (1=첫시도, 2이상=재시도)
     * - 실제 이메일 발송 (설정 시)
     */
    public void sendEmail(BillingMessageDto message, int deliveryAttempt) throws Exception {
//        log.info("📧 Sending email to: {} (billId={})",
//                message.getRecipientEmail(), message.getBillId());
        
        // 1초 지연 (네트워크 지연 시뮬레이션)
        Thread.sleep(1000);
        
        // 시도 횟수에 따른 실패율 적용
        int failureRate = (deliveryAttempt == 1) ? initialFailureRate : retryFailureRate;
        if (random.nextInt(100) < failureRate) {
            log.error("❌ [의도적 실패] 시도 {}회, 실패율 {}%, billId={}",
            		deliveryAttempt, failureRate, message.getBillId());
            throw new RuntimeException(String.format(
                "Email send failed (attempt=%d, failureRate=%d%%, SMTP error simulation)", 
                deliveryAttempt, failureRate));
        }
        
        // 실제 이메일 발송
        if (realEmailEnabled) {
            try {
                sendRealEmail(message);
                log.info("📬 Real email sent to: {}", testRecipient);
            } catch (Exception e) {
                log.warn("⚠️ Real email send failed: {}", e.getMessage());
                // 실제 발송 실패는 시뮬레이션에 영향 주지 않음
            }
        }
        
        //log.info("✅ Email sent successfully. billId={}, amount={}",
        //        message.getBillId(), message.getTotalAmount());
    }
    
    /**
     * 실제 이메일 발송
     */
    private void sendRealEmail(BillingMessageDto message) throws Exception {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
        
        // 수신자: 테스트용 개발자 이메일
        helper.setTo(testRecipient);
        helper.setSubject(String.format("[LG U+] %s 청구서 도착", message.getBillYearMonth()));
        helper.setText(createEmailBody(message), true);
        
        mailSender.send(mimeMessage);
    }
    
    /**
     * 이메일 본문 생성 (HTML)
     */
    private String createEmailBody(BillingMessageDto message) {
        NumberFormat currencyFormat = NumberFormat.getInstance(Locale.KOREA);
        
        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: 'Malgun Gothic', sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); 
                              color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }
                    .content { background: #f9f9f9; padding: 30px; border: 1px solid #ddd; }
                    .amount { font-size: 32px; font-weight: bold; color: #e91e63; margin: 20px 0; }
                    .detail { background: white; padding: 15px; margin: 10px 0; border-radius: 5px; }
                    .detail-row { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid #eee; }
                    .label { color: #666; }
                    .value { font-weight: bold; }
                    .footer { text-align: center; padding: 20px; color: #999; font-size: 12px; }
                    .button { display: inline-block; background: #667eea; color: white; 
                              padding: 12px 30px; text-decoration: none; border-radius: 5px; margin-top: 20px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>📱 LG U+ 청구서</h1>
                        <p>%s 요금 안내</p>
                    </div>
                    
                    <div class="content">
                        <h2>청구 금액</h2>
                        <div class="amount">%s원</div>
                        
                        <div class="detail">
                            <div class="detail-row">
                                <span class="label">요금제</span>
                                <span class="value">%s</span>
                            </div>
                            <div class="detail-row">
                                <span class="label">월정액</span>
                                <span class="value">%s원</span>
                            </div>
                            <div class="detail-row">
                                <span class="label">부가서비스</span>
                                <span class="value">%s원</span>
                            </div>
                            <div class="detail-row">
                                <span class="label">소액결제</span>
                                <span class="value">%s원</span>
                            </div>
                        </div>
                        
                        <div style="margin-top: 20px; padding: 15px; background: #fff3cd; border-left: 4px solid #ffc107; border-radius: 5px;">
                            <strong>📅 납부 기한:</strong> %s
                        </div>
                        
                        <div style="text-align: center;">
                            <a href="#" class="button">상세내역 확인</a>
                        </div>
                    </div>
                    
                    <div class="footer">
                        <p>본 메일은 발신 전용입니다.</p>
                        <p>© 2025 LG U+. All rights reserved.</p>
                        <p style="color: #ccc; font-size: 10px;">BillID: %d | UserID: %d</p>
                    </div>
                </div>
            </body>
            </html>
            """,
            message.getBillYearMonth(),
            currencyFormat.format(message.getTotalAmount()),
            message.getPlanName() != null ? message.getPlanName() : "5G 프리미어",
            currencyFormat.format(message.getPlanFee() != null ? message.getPlanFee() : 0),
            currencyFormat.format(message.getAddonFee() != null ? message.getAddonFee() : 0),
            currencyFormat.format(message.getMicroPaymentFee() != null ? message.getMicroPaymentFee() : 0),
            message.getDueDate() != null ? message.getDueDate() : "미정",
            message.getBillId(),
            message.getUserId()
        );
    }
}