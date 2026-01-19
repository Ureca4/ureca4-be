package com.ureca.billing.batch.util;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
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

        // BASE_FEE
        Integer baseFee = jdbcTemplate.queryForObject(
    		"""
            SELECT p.monthly_fee
            FROM PLANS p
            JOIN USER_PLANS up ON p.plan_id = up.plan_id
            WHERE up.user_id = ?
              AND up.status = 'ACTIVE'
            """,
            Integer.class,
            userId
        );
        if (baseFee != null) {
            BillDetail d = new BillDetail();
            d.setDetailType("PLAN");
            d.setChargeCategory(ChargeCategory.BASE_FEE);
            d.setAmount(baseFee.longValue());
            details.add(d);
        }

        // ADDON_FEE
        jdbcTemplate.query(
            """
            SELECT a.monthly_fee FROM ADDONS a
            JOIN USER_ADDONS ua ON a.addon_id = ua.addon_id
            WHERE ua.user_id = ? AND ua.status = 'ACTIVE'
            """,
            rs -> {
                BillDetail d = new BillDetail();
                d.setDetailType("ADDON");
                d.setChargeCategory(ChargeCategory.ADDON_FEE);
                d.setAmount(rs.getLong("monthly_fee"));
                details.add(d);
            },
            userId
        );

        // MICRO_PAYMENT
        jdbcTemplate.query(
            """
            SELECT amount FROM MICRO_PAYMENTS
            WHERE user_id = ?
              AND DATE_FORMAT(payment_date, '%Y-%m') = ?
            """,
            rs -> {
                BillDetail d = new BillDetail();
                d.setDetailType("MICRO_PAYMENT");
                d.setChargeCategory(ChargeCategory.MICRO_PAYMENT);
                d.setAmount(rs.getLong("amount"));
                details.add(d);
            },
            userId,
            billingMonth.toString()
        );

        return new BillingResult(bill, details);
    }
}
