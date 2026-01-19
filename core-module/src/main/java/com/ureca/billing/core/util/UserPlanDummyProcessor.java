package com.ureca.billing.core.util;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import com.ureca.billing.core.entity.UserPlanStatus;
import com.ureca.billing.core.entity.UserPlans;
import com.ureca.billing.core.entity.Users;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class UserPlanDummyProcessor
        implements ItemProcessor<Users, UserPlans> {

    private final List<Long> planIds;

    public UserPlanDummyProcessor(JdbcTemplate jdbcTemplate) {
        this.planIds = jdbcTemplate.queryForList(
            "SELECT plan_id FROM PLANS WHERE is_active = true",
            Long.class
        );
    }

    @Override
    public UserPlans process(Users user) {

        Long planId = randomPlan();

        LocalDate startDate = user.getCreatedAt()
            .toLocalDate();

        return new UserPlans(
            user.getUserId(),
            planId,
            startDate,
            null,
            UserPlanStatus.ACTIVE
        );
    }

    private Long randomPlan() {
        return planIds.get(ThreadLocalRandom.current()
            .nextInt(planIds.size()));
    }
}
