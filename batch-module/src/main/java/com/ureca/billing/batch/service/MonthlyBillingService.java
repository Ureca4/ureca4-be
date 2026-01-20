package com.ureca.billing.batch.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ureca.billing.core.dto.BillingMessageDto;
import lombok.Builder;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MonthlyBillingService {

    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcTemplate jdbcTemplate;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topics.billing-notification}")
    private String billingTopic;

    @Transactional
    public void process(List<Long> userIds, YearMonth billingMonth) {

        if (userIds == null || userIds.isEmpty()) return;

        LocalDate monthStart = billingMonth.atDay(1);
        LocalDate nextMonthStart = billingMonth.plusMonths(1).atDay(1);
        DateTimeFormatter ymdFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        Map<String, Object> params = Map.of(
                "userIds", userIds,
                "monthStart", monthStart,
                "nextMonthStart", nextMonthStart,
                "billingMonth", billingMonth.toString()
        );

        Map<Long, UserContactInfo> userContacts = new HashMap<>();
        namedJdbc.query("""
            SELECT user_id, email_cipher, phone_cipher, notification_type, name
            FROM USERS
            WHERE user_id IN (:userIds)
        """, params, (RowCallbackHandler) rs -> {
            userContacts.put(rs.getLong("user_id"), UserContactInfo.builder()
                    .emailCipher(rs.getString("email_cipher"))
                    .phoneCipher(rs.getString("phone_cipher"))
                    .notificationType(rs.getString("notification_type"))
                    .name(rs.getString("name"))
                    .build());
        });

        /* =========================
         * 1️⃣ 요금 데이터 벌크 조회
         * ========================= */

        Map<Long, Long> planFees = new HashMap<>();
        Map<Long, String> planNames = new HashMap<>();
        namedJdbc.query("""
            SELECT up.user_id, p.monthly_fee, p.plan_name
            FROM USER_PLANS up
            JOIN PLANS p ON p.plan_id = up.plan_id
            WHERE up.status = 'ACTIVE'
              AND up.user_id IN (:userIds)
        """, params, (RowCallbackHandler) rs -> {
            long uid = rs.getLong("user_id");
            planFees.put(uid, rs.getLong("monthly_fee"));
            planNames.put(uid, rs.getString("plan_name"));
        });

        Map<Long, List<Long>> addonFees = new HashMap<>();
        namedJdbc.query("""
            SELECT ua.user_id, a.monthly_fee
            FROM USER_ADDONS ua
            JOIN ADDONS a ON a.addon_id = ua.addon_id
            WHERE ua.status = 'ACTIVE'
              AND ua.user_id IN (:userIds)
        """, params, (RowCallbackHandler) rs -> {
            long uid = rs.getLong("user_id");
            addonFees
                .computeIfAbsent(uid, k -> new ArrayList<>())
                .add(rs.getLong("monthly_fee"));
        });

        Map<Long, List<Long>> microPayments = new HashMap<>();
        namedJdbc.query("""
            SELECT user_id, amount
            FROM MICRO_PAYMENTS
            WHERE user_id IN (:userIds)
              AND payment_date >= :monthStart
              AND payment_date < :nextMonthStart
        """, params, (RowCallbackHandler) rs -> {
            long uid = rs.getLong("user_id");
            microPayments
                .computeIfAbsent(uid, k -> new ArrayList<>())
                .add(rs.getLong("amount"));
        });

        /* =========================
         * 2️⃣ BILLS 벌크 INSERT
         * ========================= */

        jdbcTemplate.batchUpdate("""
            INSERT INTO BILLS
            (user_id, billing_month, settlement_date, bill_issue_date)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              bill_issue_date = VALUES(bill_issue_date)
        """, userIds, userIds.size(), (ps, uid) -> {
            ps.setLong(1, uid);
            ps.setString(2, billingMonth.toString());
            ps.setObject(3, billingMonth.atEndOfMonth());
            ps.setObject(4, LocalDate.now());
        });

        /* =========================
         * 3️⃣ bill_id 매핑
         * ========================= */

        Map<Long, Long> billIdByUser = new HashMap<>();
        namedJdbc.query("""
            SELECT bill_id, user_id
            FROM BILLS
            WHERE billing_month = :billingMonth
              AND user_id IN (:userIds)
        """, Map.of(
                "billingMonth", billingMonth.toString(),
                "userIds", userIds
        ), (RowCallbackHandler) rs -> {
            billIdByUser.put(
                rs.getLong("user_id"),
                rs.getLong("bill_id")
            );
        });

        /* =========================
         * 4️⃣ BILL_DETAILS 벌크 INSERT
         * ========================= */

        List<Object[]> rows = new ArrayList<>();

        for (Long uid : userIds) {
            Long billId = billIdByUser.get(uid);
            if (billId == null) continue;

            // 요금 계산 변수
            long currentPlanFee = 0;
            long currentAddonFee = 0;
            long currentMicroFee = 0;

            // PLAN
            Long planFee = planFees.get(uid);
            if (planFee != null) {
                rows.add(new Object[]{
                    billId,
                    "PLAN",
                    "BASE_FEE",
                    planFee,
                    uid
                });
            }

            // ADDON
            for (Long fee : addonFees.getOrDefault(uid, List.of())) {
                rows.add(new Object[]{
                    billId,
                    "ADDON",
                    "ADDON_FEE",
                    fee,
                    uid
                });
            }

            // MICRO_PAYMENT
            for (Long amt : microPayments.getOrDefault(uid, List.of())) {
                rows.add(new Object[]{
                    billId,
                    "MICRO_PAYMENT",
                    "MICRO_PAYMENT",
                    amt,
                    uid
                });
            }

            UserContactInfo contact = userContacts.get(uid);
            if (contact != null) {
                long totalAmount = currentPlanFee + currentAddonFee + currentMicroFee;

                BillingMessageDto messageDto = BillingMessageDto.builder()
                        .billId(billId)
                        .userId(uid)
                        .billYearMonth(billingMonth.toString().replace("-", ""))
                        .billDate(LocalDate.now().format(ymdFormatter))
                        .dueDate(LocalDate.now().plusDays(15).format(ymdFormatter))
                        .timestamp(LocalDateTime.now().toString())
                        .recipientEmail(contact.getEmailCipher())
                        .recipientPhone(contact.getPhoneCipher())
                        .name(contact.getName())
                        .notificationType(contact.getNotificationType())
                        .totalAmount(totalAmount)
                        .planFee(currentPlanFee)
                        .addonFee(currentAddonFee)
                        .microPaymentFee(currentMicroFee)
                        .planName(planNames.getOrDefault(uid, "기본 요금제"))
                        .build();

                kafkaTemplate.send(billingTopic, messageDto);
            }
        }

        jdbcTemplate.batchUpdate("""
            INSERT INTO BILL_DETAILS
            (bill_id, detail_type, charge_category, amount, related_user_id)
            VALUES (?, ?, ?, ?, ?)
        """, rows);
    }

    @Getter
    @Builder
    private static class UserContactInfo {
        private String emailCipher;
        private String phoneCipher;
        private String notificationType;
        private String name;
    }
}
