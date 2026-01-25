package com.ureca.billing.batch.service;

import static org.apache.kafka.common.requests.FetchMetadata.log;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

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
    public void createBills(List<Long> userIds, YearMonth billingMonth) {

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
            WHERE up.user_id IN (:userIds)
        		AND up.start_date < :nextMonthStart
        		AND (up.end_date IS NULL OR up.end_date >= :monthStart)
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
            WHERE ua.user_id IN (:userIds)
        		AND ua.start_date < :nextMonthStart
        		AND (ua.end_date IS NULL OR ua.end_date >= :monthStart)

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
    }

    /**
     * [Step 2] 알림 이벤트 생성 (Outbox Events)
     * - Step 1에서 생성된 billId 목록을 받아서 실행됨
     * - 별도의 트랜잭션으로 실행됨
     */
    @Transactional
    public void createOutboxEvents(List<Long> billIds) {
        if (billIds == null || billIds.isEmpty()) return;

        Map<String, Object> params = Map.of("billIds", billIds);
        DateTimeFormatter ymdFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // 1. 필요한 데이터 다시 조회 (트랜잭션이 분리되었으므로 DB에서 가져와야 함)

        // 1-1) User 정보 및 Bill 기본 정보 조회
        List<BillInfo> billInfos = new ArrayList<>();
        namedJdbc.query("""
            SELECT b.bill_id, b.user_id, b.billing_month,
                   u.email_cipher, u.phone_cipher, u.name
            FROM BILLS b
            JOIN USERS u ON u.user_id = b.user_id
            WHERE b.bill_id IN (:billIds)
        """, params, (RowCallbackHandler) rs -> {
            billInfos.add(BillInfo.builder()
                    .billId(rs.getLong("bill_id"))
                    .userId(rs.getLong("user_id"))
                    .billingMonth(rs.getString("billing_month"))
                    .emailCipher(rs.getString("email_cipher"))
                    .phoneCipher(rs.getString("phone_cipher"))
                    .name(rs.getString("name"))
                    .build());
        });

        Set<Long> userIds = billInfos.stream().map(BillInfo::getUserId).collect(Collectors.toSet());
        Map<String, Object> userParams = Map.of("userIds", userIds);

        // 1-2) 총 요금 합계 계산 (DETAILS 테이블 Group By)
        // Step 1에서 계산했지만 메모리에 없으므로 다시 계산하는 것이 안전함
        Map<Long, Long> totalAmountByBillId = new HashMap<>();
        namedJdbc.query("""
            SELECT bill_id, SUM(amount) as total_amt
            FROM BILL_DETAILS
            WHERE bill_id IN (:billIds)
            GROUP BY bill_id
        """, params, (RowCallbackHandler) rs -> {
            totalAmountByBillId.put(rs.getLong("bill_id"), rs.getLong("total_amt"));
        });

        // 1-3) Plan 이름 조회 (알림용) - BILL_DETAILS나 USER_PLANS 조인 필요
        // 단순화를 위해 생략하거나 필요시 추가 조회

        // 1-4) 알림 채널 조회
        Map<Long, Set<String>> channelsByUser = new HashMap<>();
        namedJdbc.query("""
            SELECT user_id, channel
            FROM USER_NOTIFICATION_PREFS
            WHERE enabled = 1
              AND user_id IN (:userIds)
              AND channel IN ('EMAIL','SMS','PUSH')
        """, userParams, (RowCallbackHandler) rs -> {
            channelsByUser.computeIfAbsent(rs.getLong("user_id"), k -> new HashSet<>())
                    .add(rs.getString("channel"));
        });

        // 2. Outbox 데이터 생성
        List<Object[]> outboxRows = new ArrayList<>();
        String eventType = "BILLING_NOTIFY";

        for (BillInfo info : billInfos) {
            Set<String> channels = channelsByUser.getOrDefault(info.getUserId(), Set.of());
            if (channels.isEmpty()) continue;

            long totalAmount = totalAmountByBillId.getOrDefault(info.getBillId(), 0L);

            for (String ch : channels) {
                String eventId = UUID.randomUUID().toString();
                try {
                    BillingMessageDto messageDto = BillingMessageDto.builder()
                            .billId(info.getBillId())
                            .userId(info.getUserId())
                            .billYearMonth(info.getBillingMonth().replace("-", ""))
                            .billDate(LocalDate.now().format(ymdFormatter))
                            .dueDate(LocalDate.now().plusDays(15).format(ymdFormatter))
                            .timestamp(LocalDateTime.now().toString())
                            .recipientEmail(info.getEmailCipher())
                            .recipientPhone(info.getPhoneCipher())
                            .name(info.getName())
                            .totalAmount(totalAmount)
                            .planName("청구서 참조") // 상세 로직 필요시 추가 구현
                            .notificationType(ch)
                            .build();

                    String payload = objectMapper.writeValueAsString(messageDto);

                    outboxRows.add(new Object[]{
                            eventId, info.getBillId(), info.getUserId(),
                            eventType, ch, payload
                    });

                } catch (JsonProcessingException e) {
                    log.error("JSON 변환 실패: billId={}", info.getBillId(), e);
                }
            }
        }

        // 3. DB 저장
        jdbcTemplate.batchUpdate("""
            INSERT INTO OUTBOX_EVENTS
              (event_id, bill_id, user_id, event_type, notification_type, payload, status, attempt_count)
            VALUES
              (?, ?, ?, ?, ?, CAST(? AS JSON), 'READY', 0)
            ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP
        """, outboxRows);
    }



    @Getter
    @Builder
    private static class UserContactInfo {
        private String emailCipher;
        private String phoneCipher;
        private String name;
    }

    @Getter
    @Builder
    private static class BillInfo {
        private Long billId;
        private Long userId;
        private String billingMonth;
        private String emailCipher;
        private String phoneCipher;
        private String name;
    }
}
