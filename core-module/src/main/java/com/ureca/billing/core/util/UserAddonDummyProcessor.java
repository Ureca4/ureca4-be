package com.ureca.billing.core.util;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ureca.billing.core.entity.AddonStatus;
import com.ureca.billing.core.entity.UserAddons;

@Component
@StepScope
public class UserAddonDummyProcessor
        implements ItemProcessor<Long, UserAddons> {

    private final List<Long> userIds;
    private final List<Long> addonIds;

    // 유저별 이미 할당된 addon 기록
    private final Map<Long, Set<Long>> assignedAddons = new HashMap<>();

    public UserAddonDummyProcessor(JdbcTemplate jdbcTemplate) {
        this.userIds = jdbcTemplate.queryForList(
            "SELECT user_id FROM USERS",
            Long.class
        );

        this.addonIds = jdbcTemplate.queryForList(
            "SELECT addon_id FROM ADDONS",
            Long.class
        );
    }

    @Override
    public UserAddons process(Long seq) {

        Long userId = randomUserId();

        // 유저가 가진 addon 목록
        Set<Long> userAddonSet =
            assignedAddons.computeIfAbsent(userId, k -> new HashSet<>());

        // 이미 모든 addon을 다 가졌으면 skip
        if (userAddonSet.size() >= addonIds.size()) {
            return null; // writer로 안 넘어감
        }

        Long addonId;
        do {
            addonId = randomAddonId();
        } while (userAddonSet.contains(addonId));

        userAddonSet.add(addonId);

        LocalDate startDate = LocalDate.now();
            //.minusDays(ThreadLocalRandom.current().nextInt(0, 365));

        return new UserAddons(
            userId,
            addonId,
            startDate,
            null,
            AddonStatus.ACTIVE
        );
    }

    private Long randomUserId() {
        return userIds.get(ThreadLocalRandom.current()
            .nextInt(userIds.size()));
    }

    private Long randomAddonId() {
        return addonIds.get(ThreadLocalRandom.current()
            .nextInt(addonIds.size()));
    }
}
