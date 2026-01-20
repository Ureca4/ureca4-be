package com.ureca.billing.core.util;

import java.sql.Time;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import com.ureca.billing.core.entity.UserNotificationPrefs;
import com.ureca.billing.core.entity.Users;

@Component
@StepScope
public class UserNotificationPrefsDummyProcessor
        implements ItemProcessor<Users, UserNotificationPrefs> {

    private static final String[] CHANNELS = {"EMAIL", "SMS", "PUSH"};

    @Override
    public UserNotificationPrefs process(Users user) {

        UserNotificationPrefs prefs = new UserNotificationPrefs();

        prefs.setUserId(user.getUserId());

        prefs.setChannel(
            CHANNELS[ThreadLocalRandom.current().nextInt(CHANNELS.length)]
        );

        prefs.setEnabled(true);
        prefs.setPriority(1);

        // quiet time: 10%만 설정
        if (ThreadLocalRandom.current().nextInt(10) == 0) {

            // 22:00 ~ 02:00 (다음날 새벽 포함)
            int startHour;
            if (ThreadLocalRandom.current().nextBoolean()) {
                startHour = ThreadLocalRandom.current().nextInt(22, 24);
            } else {
                startHour = ThreadLocalRandom.current().nextInt(0, 2);
            }

            int startMinute = ThreadLocalRandom.current().nextInt(0, 60);

            LocalTime localStart = LocalTime.of(startHour, startMinute);

            // 6~9시간 뒤 종료
            LocalTime localEnd =
                localStart.plusHours(
                    ThreadLocalRandom.current().nextInt(6, 10)
                );

            prefs.setQuietStart(Time.valueOf(localStart));
            prefs.setQuietEnd(Time.valueOf(localEnd));

        } else {
            prefs.setQuietStart(null);
            prefs.setQuietEnd(null);
        }

        LocalDateTime now = LocalDateTime.now();
        prefs.setCreatedAt(now);
        prefs.setUpdatedAt(now);

        return prefs;
    }
}
