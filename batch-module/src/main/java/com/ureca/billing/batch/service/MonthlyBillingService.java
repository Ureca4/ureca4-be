package com.ureca.billing.batch.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.core.dto.BillingMessageDto;
import com.ureca.billing.core.security.crypto.AesUtil;
import com.ureca.billing.core.security.crypto.CryptoKeyProvider;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class MonthlyBillingService {

    private static final int IN_CLAUSE_SIZE = 10000;
    private static final int DETAIL_BATCH_SIZE = 10000;

    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CryptoKeyProvider keyProvider;

    /* =====================================================
     * Step 1 : BILLS / BILL_DETAILS 생성
     * ===================================================== */
    @Transactional
    public void createBills(List<Long> userIds, YearMonth billingMonth) {

        if (userIds == null || userIds.isEmpty()) return;

        LocalDate monthStart = billingMonth.atDay(1);
        LocalDate nextMonthStart = billingMonth.plusMonths(1).atDay(1);

        /* =========================
         * 1️⃣ 사용자 정보 조회
         * ========================= */
        Map<Long, UserContactInfo> userContacts = new HashMap<>();

        for (List<Long> part : partition(userIds)) {
            namedJdbc.query("""
                SELECT user_id, email_cipher, phone_cipher, name
                FROM USERS
                WHERE user_id IN (:userIds)
            """, Map.of("userIds", part),
            (RowCallbackHandler) rs -> {
                userContacts.put(
                        rs.getLong("user_id"),
                        UserContactInfo.builder()
                                .emailCipher(rs.getString("email_cipher"))
                                .phoneCipher(rs.getString("phone_cipher"))
                                .name(rs.getString("name"))
                                .build()
                );
            });
        }

        /* =========================
         * 2️⃣ 요금 데이터 조회
         * ========================= */
        Map<Long, Long> planFees = new HashMap<>();
        Map<Long, List<Long>> addonFees = new HashMap<>();
        Map<Long, List<Long>> microPayments = new HashMap<>();

        for (List<Long> part : partition(userIds)) {
            Map<String, Object> params = Map.of(
                    "userIds", part,
                    "monthStart", monthStart,
                    "nextMonthStart", nextMonthStart
            );

            // PLAN
            namedJdbc.query("""
                SELECT up.user_id, p.monthly_fee
                FROM USER_PLANS up
                JOIN PLANS p ON p.plan_id = up.plan_id
                WHERE up.user_id IN (:userIds)
                  AND up.start_date < :nextMonthStart
                  AND (up.end_date IS NULL OR up.end_date >= :monthStart)
            """, params,
            (RowCallbackHandler) rs ->
                    planFees.put(rs.getLong("user_id"), rs.getLong("monthly_fee"))
            );

            // ADDON
            namedJdbc.query("""
                SELECT ua.user_id, a.monthly_fee
                FROM USER_ADDONS ua
                JOIN ADDONS a ON a.addon_id = ua.addon_id
                WHERE ua.user_id IN (:userIds)
                  AND ua.start_date < :nextMonthStart
                  AND (ua.end_date IS NULL OR ua.end_date >= :monthStart)
            """, params,
            (RowCallbackHandler) rs ->
                    addonFees
                            .computeIfAbsent(rs.getLong("user_id"), k -> new ArrayList<>())
                            .add(rs.getLong("monthly_fee"))
            );

            // MICRO PAYMENT
            namedJdbc.query("""
                SELECT user_id, amount
                FROM MICRO_PAYMENTS
                WHERE user_id IN (:userIds)
                  AND payment_date >= :monthStart
                  AND payment_date < :nextMonthStart
            """, params,
            (RowCallbackHandler) rs ->
                    microPayments
                            .computeIfAbsent(rs.getLong("user_id"), k -> new ArrayList<>())
                            .add(rs.getLong("amount"))
            );
        }

        /* =========================
         * 3️⃣ BILLS INSERT
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
         * 4️⃣ bill_id 매핑
         * ========================= */
        Map<Long, Long> billIdByUser = new HashMap<>();

        for (List<Long> part : partition(userIds)) {
            namedJdbc.query("""
                SELECT bill_id, user_id
                FROM BILLS
                WHERE billing_month = :billingMonth
                  AND user_id IN (:userIds)
            """, Map.of(
                    "billingMonth", billingMonth.toString(),
                    "userIds", part
            ),
            (RowCallbackHandler) rs ->
                    billIdByUser.put(rs.getLong("user_id"), rs.getLong("bill_id"))
            );
        }

        /* =========================
         * 5️⃣ BILL_DETAILS batch insert
         * ========================= */
        List<Object[]> buffer = new ArrayList<>(DETAIL_BATCH_SIZE);

        for (Long uid : userIds) {
            Long billId = billIdByUser.get(uid);
            if (billId == null) continue;

            if (planFees.containsKey(uid)) {
                buffer.add(new Object[]{
                        billId, "PLAN", "BASE_FEE", planFees.get(uid), uid
                });
            }

            for (Long fee : addonFees.getOrDefault(uid, List.of())) {
                buffer.add(new Object[]{
                        billId, "ADDON", "ADDON_FEE", fee, uid
                });
            }

            for (Long amt : microPayments.getOrDefault(uid, List.of())) {
                buffer.add(new Object[]{
                        billId, "MICRO_PAYMENT", "MICRO_PAYMENT", amt, uid
                });
            }

            if (buffer.size() >= DETAIL_BATCH_SIZE) {
                flushBillDetails(buffer);
            }
        }

        flushBillDetails(buffer);
    }

    private void flushBillDetails(List<Object[]> buffer) {
        if (buffer.isEmpty()) return;

        jdbcTemplate.batchUpdate("""
            INSERT INTO BILL_DETAILS
              (bill_id, detail_type, charge_category, amount, related_user_id)
            VALUES (?, ?, ?, ?, ?)
        """, buffer);

        buffer.clear();
    }

    /* =====================================================
     * Step 2 : Outbox 이벤트 생성
     * ===================================================== */
    @Transactional
    public void createOutboxEvents(List<Long> billIds) {

        if (billIds == null || billIds.isEmpty()) return;

        DateTimeFormatter ymd = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        List<BillInfo> billInfos = new ArrayList<>();

        for (List<Long> part : partition(billIds)) {
            namedJdbc.query("""
                SELECT b.bill_id, b.user_id, b.billing_month,
                       u.email_cipher, u.phone_cipher, u.name
                FROM BILLS b
                JOIN USERS u ON u.user_id = b.user_id
                WHERE b.bill_id IN (:billIds)
            """, Map.of("billIds", part),
            (RowCallbackHandler) rs ->
                    billInfos.add(BillInfo.builder()
                            .billId(rs.getLong("bill_id"))
                            .userId(rs.getLong("user_id"))
                            .billingMonth(rs.getString("billing_month"))
                            .emailCipher(rs.getString("email_cipher"))
                            .phoneCipher(rs.getString("phone_cipher"))
                            .name(rs.getString("name"))
                            .build())
            );
        }

        Map<Long, Long> totalAmountByBill = new HashMap<>();
        for (List<Long> part : partition(billIds)) {
            namedJdbc.query("""
                SELECT bill_id, SUM(amount) total_amt
                FROM BILL_DETAILS
                WHERE bill_id IN (:billIds)
                GROUP BY bill_id
            """, Map.of("billIds", part),
            (RowCallbackHandler) rs ->
                    totalAmountByBill.put(rs.getLong("bill_id"), rs.getLong("total_amt"))
            );
        }

        List<Object[]> outboxRows = new ArrayList<>();

        for (BillInfo info : billInfos) {
            long total = totalAmountByBill.getOrDefault(info.getBillId(), 0L);

            try {
                BillingMessageDto dto = BillingMessageDto.builder()
                        .billId(info.getBillId())
                        .userId(info.getUserId())
                        .billYearMonth(info.getBillingMonth().replace("-", ""))
                        .billDate(LocalDate.now().format(ymd))
                        .dueDate(LocalDate.now().plusDays(15).format(ymd))
                        .timestamp(LocalDateTime.now().toString())
                        .recipientEmail(info.getEmailCipher())
                        .recipientPhone(info.getPhoneCipher())
                        .name(info.getName())
                        .totalAmount(total)
                        .notificationType("EMAIL")
                        .build();

                String payload = AesUtil.encrypt(
                        objectMapper.writeValueAsString(dto),
                        keyProvider.getCurrentKey()
                );

                outboxRows.add(new Object[]{
                        UUID.randomUUID().toString(),
                        info.getBillId(),
                        info.getUserId(),
                        "BILLING_NOTIFY",
                        "EMAIL",
                        payload
                });

            } catch (Exception e) {
                log.error("Outbox 생성 실패 billId={}", info.getBillId(), e);
            }
        }

        jdbcTemplate.batchUpdate("""
            INSERT INTO OUTBOX_EVENTS
              (event_id, bill_id, user_id, event_type, notification_type, payload, status, attempt_count)
            VALUES (?, ?, ?, ?, ?, ?, 'READY', 0)
        """, outboxRows);
    }

    /* =========================
     * 공통 util
     * ========================= */
    private List<List<Long>> partition(List<Long> source) {
        List<List<Long>> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i += IN_CLAUSE_SIZE) {
            result.add(source.subList(i, Math.min(i + IN_CLAUSE_SIZE, source.size())));
        }
        return result;
    }

    @Getter @Builder
    private static class UserContactInfo {
        private String emailCipher;
        private String phoneCipher;
        private String name;
    }

    @Getter @Builder
    private static class BillInfo {
        private Long billId;
        private Long userId;
        private String billingMonth;
        private String emailCipher;
        private String phoneCipher;
        private String name;
    }
}
