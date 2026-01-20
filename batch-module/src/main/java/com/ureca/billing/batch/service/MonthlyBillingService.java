package com.ureca.billing.batch.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MonthlyBillingService {

    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void process(List<Long> userIds, YearMonth billingMonth) {

        if (userIds == null || userIds.isEmpty()) return;

        LocalDate monthStart = billingMonth.atDay(1);
        LocalDate nextMonthStart = billingMonth.plusMonths(1).atDay(1);

        Map<String, Object> params = Map.of(
                "userIds", userIds,
                "monthStart", monthStart,
                "nextMonthStart", nextMonthStart
        );

        /* =========================
         * 1️⃣ 요금 데이터 벌크 조회
         * ========================= */

        Map<Long, Long> planFees = new HashMap<>();
        namedJdbc.query("""
            SELECT up.user_id, p.monthly_fee
            FROM USER_PLANS up
            JOIN PLANS p ON p.plan_id = up.plan_id
            WHERE up.status = 'ACTIVE'
              AND up.user_id IN (:userIds)
        """, params, (RowCallbackHandler) rs -> {
            planFees.put(
                rs.getLong("user_id"),
                rs.getLong("monthly_fee")
            );
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
        }

        jdbcTemplate.batchUpdate("""
            INSERT INTO BILL_DETAILS
            (bill_id, detail_type, charge_category, amount, related_user_id)
            VALUES (?, ?, ?, ?, ?)
        """, rows);

        /* =========================
         * 5️⃣ OUTBOX_EVENTS 생성 (Transactional Outbox)
         *     - user별 enabled 채널 모두 생성
         *     - OUTBOX는 READY로만 쌓고 끝(Producer가 가져감)
         * ========================= */

        // 5-1) prefs 조회: (EMAIL/SMS만) enabled=1
        Map<Long, List<String>> channelsByUser = new HashMap<>();
        namedJdbc.query("""
            SELECT user_id, channel
            FROM USER_NOTIFICATION_PREFS
            WHERE enabled = 1
              AND user_id IN (:userIds)
              AND channel IN ('EMAIL','SMS','PUSH')   -- OUTBOX enum이 EMAIL/SMS만이라 일단 제한
        """, Map.of("userIds", userIds), (RowCallbackHandler) rs -> {
            long uid = rs.getLong("user_id");
            String channel = rs.getString("channel");
            channelsByUser.computeIfAbsent(uid, k -> new ArrayList<>()).add(channel);
        });

        // 5-2) outbox row 생성
        List<Object[]> outboxRows = new ArrayList<>();
        String eventType = "BILLING_NOTIFY"; // 너희 OUTBOX 기본값과 맞춤

        for (Long uid : userIds) {
            Long billId = billIdByUser.get(uid);
            if (billId == null) continue;

            List<String> channels = channelsByUser.getOrDefault(uid, List.of());
            if (channels.isEmpty()) continue; // 정책상 최소 1개 보장하면 보통 안 비어야 함

            for (String ch : channels) {
                String eventId = UUID.randomUUID().toString();

                // Kafka에 보낼 최소 payload (JSON)
                String payload = """
                  {"schemaVersion":1,"eventId":"%s","eventType":"%s","billId":%d,"userId":%d,"billingMonth":"%s"}
                  """.formatted(eventId, eventType, billId, uid, billingMonth);

                outboxRows.add(new Object[]{
                        eventId,
                        billId,
                        uid,
                        eventType,
                        ch,          // OUTBOX_EVENTS.notification_type (ENUM)
                        payload
                });
            }
        }

        // 5-3) 멱등(중복 생성 방지): UNIQUE(bill_id, notification_type, event_type) 활용
        // 이미 존재하면 payload/status만 갱신하고 READY로 되돌리는 방식 추천
        jdbcTemplate.batchUpdate("""
            INSERT INTO OUTBOX_EVENTS
              (event_id, bill_id, user_id, event_type, notification_type, payload, status, attempt_count, next_retry_at, last_error)
            VALUES
              (?, ?, ?, ?, ?, CAST(? AS JSON), 'READY', 0, NULL, NULL)
            ON DUPLICATE KEY UPDATE
              payload = VALUES(payload),
              status = 'READY',
              next_retry_at = NULL,
              last_error = NULL,
              updated_at = CURRENT_TIMESTAMP
        """, outboxRows);
    }
}
