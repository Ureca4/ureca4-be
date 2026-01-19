package com.ureca.billing.batch.util;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ureca.billing.core.entity.Bill;
import com.ureca.billing.core.entity.BillDetail;
import com.ureca.billing.core.entity.ChargeCategory;

@Component
@StepScope
public class MonthlyBillingProcessor
        implements ItemProcessor<Long, BillingResult> {

    private final JdbcTemplate jdbcTemplate;
    private final YearMonth billingMonth;

    public MonthlyBillingProcessor(@Value("#{jobParameters['billingMonth'] ?: T(java.time.YearMonth).now().toString()}")
    	String billingMonth, JdbcTemplate jdbcTemplate) {
        this.billingMonth = YearMonth.parse(billingMonth);
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public BillingResult process(Long userId) {

        Bill bill = new Bill();
        bill.setUserId(userId);
        bill.setBillingMonth(billingMonth.toString());
        bill.setSettlementDate(billingMonth.atEndOfMonth());
        bill.setBillIssueDate(LocalDate.now());

        List<BillDetail> details = new ArrayList<>();

        long planFee = 0L;
        long addonFee = 0L;
        long microPaymentFee = 0L;
        String planName = "Unknown";

        // BASE_FEE
        // 2. 기본요금 (Plan)
        try {
            Map<String, Object> planMap = jdbcTemplate.queryForMap(
                    """
                    SELECT p.monthly_fee, p.plan_name 
                    FROM PLANS p
                    JOIN USER_PLANS up ON p.plan_id = up.plan_id
                    WHERE up.user_id = ? AND up.status = 'ACTIVE'
                    """, userId
            );
            planFee = ((Number) planMap.get("monthly_fee")).longValue();
            planName = (String) planMap.get("plan_name");

            details.add(createDetail("PLAN", ChargeCategory.BASE_FEE, planFee));
        } catch (EmptyResultDataAccessException ignored) {}

        // 3. 부가서비스 (Addon)
        List<Long> addons = jdbcTemplate.query(
                """
                SELECT a.monthly_fee FROM ADDONS a
                JOIN USER_ADDONS ua ON a.addon_id = ua.addon_id
                WHERE ua.user_id = ? AND ua.status = 'ACTIVE'
                """,
                (rs, rowNum) -> rs.getLong("monthly_fee"), userId
        );
        for (Long fee : addons) {
            addonFee += fee;
            details.add(createDetail("ADDON", ChargeCategory.ADDON_FEE, fee));
        }

        // 4. 소액결제 (MicroPayment)
        List<Long> micros = jdbcTemplate.query(
                """
                SELECT amount FROM MICRO_PAYMENTS
                WHERE user_id = ? AND DATE_FORMAT(payment_date, '%Y-%m') = ?
                """,
                (rs, rowNum) -> rs.getLong("amount"), userId, billingMonth.toString()
        );
        for (Long fee : micros) {
            microPaymentFee += fee;
            details.add(createDetail("MICRO_PAYMENT", ChargeCategory.MICRO_PAYMENT, fee));
        }

        // 5. 유저 정보
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT email_cipher, phone_cipher FROM USERS WHERE user_id = ?", userId
        );

        // 6. 결과 반환 (Lombok Builder 사용으로 깔끔해짐)
        return BillingResult.builder()
                .bill(bill)
                .billDetail(details)
                .planName(planName)
                .emailCipher((String) user.get("email_cipher"))
                .phoneCipher((String) user.get("phone_cipher"))
                .planFee(planFee)
                .addonFee(addonFee)
                .microPaymentFee(microPaymentFee)
                .build();
    }

    // 반복되는 BillDetail 생성 로직 헬퍼 메서드
    private BillDetail createDetail(String type, ChargeCategory category, long amount) {
        BillDetail d = new BillDetail();
        d.setDetailType(type);
        d.setChargeCategory(category);
        d.setAmount(amount);
        return d;
    }
}
