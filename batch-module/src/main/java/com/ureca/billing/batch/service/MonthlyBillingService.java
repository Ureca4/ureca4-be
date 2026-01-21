package com.ureca.billing.batch.service;

import static org.apache.kafka.common.requests.FetchMetadata.log;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.billing.core.dto.BillingMessageDto;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MonthlyBillingService {

    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;



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
            SELECT user_id, email_cipher, phone_cipher, name
            FROM USERS
            WHERE user_id IN (:userIds)
        """, params, (RowCallbackHandler) rs -> {
            userContacts.put(rs.getLong("user_id"), UserContactInfo.builder()
                    .emailCipher(rs.getString("email_cipher"))
                    .phoneCipher(rs.getString("phone_cipher"))
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
        Map<Long, Set<String>> channelsByUser = new HashMap<>();
        namedJdbc.query("""
            SELECT user_id, channel
            FROM USER_NOTIFICATION_PREFS
            WHERE enabled = 1
              AND user_id IN (:userIds)
              AND channel IN ('EMAIL','SMS','PUSH')   -- OUTBOX enum이 EMAIL/SMS만이라 일단 제한
        """, Map.of("userIds", userIds), (RowCallbackHandler) rs -> {
            long uid = rs.getLong("user_id");
            String channel = rs.getString("channel");
            channelsByUser.computeIfAbsent(uid, k -> new HashSet<>()).add(channel);
        });

        // 5-2) outbox row 생성
        List<Object[]> outboxRows = new ArrayList<>();
        String eventType = "BILLING_NOTIFY";

        for (Long uid : userIds) {
            Long billId = billIdByUser.get(uid);
            if (billId == null) continue;

            Set<String> channels = channelsByUser.getOrDefault(uid, Set.of());
            if (channels.isEmpty()) continue;

            UserContactInfo contact = userContacts.get(uid);
            if (contact == null) continue;

            // 요금 합계 계산 (DTO 생성을 위해 다시 계산)
            long planFee = planFees.getOrDefault(uid, 0L);
            long addonTotal = addonFees.getOrDefault(uid, List.of()).stream().mapToLong(Long::longValue).sum();
            long microTotal = microPayments.getOrDefault(uid, List.of()).stream().mapToLong(Long::longValue).sum();
            long totalAmount = planFee + addonTotal + microTotal;

            for (String ch : channels) {
                String eventId = UUID.randomUUID().toString();

                try {
                    // 1. DTO 생성 (모든 정보 포함)
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
                            .totalAmount(totalAmount)
                            .planName(planNames.getOrDefault(uid, "기본 요금제"))
                            .notificationType(ch)
                            .build();

                    // 2. JSON 변환 (Fat Payload)
                    String payload = objectMapper.writeValueAsString(messageDto);

                    outboxRows.add(new Object[]{
                            eventId,
                            billId,
                            uid,
                            eventType,
                            ch,          // OUTBOX_EVENTS.notification_type
                            payload      // Full JSON Data
                    });

                } catch (JsonProcessingException e) {
                    log.error("JSON 변환 실패: userId={}", uid, e);
                }
            }
        }

        // 5-3) DB 저장 (Batch Insert)
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
    @Getter
    @Builder
    private static class UserContactInfo {
        private String emailCipher;
        private String phoneCipher;
        private String name;
    }
}
