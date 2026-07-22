package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.RunAdmissionProperties;
import com.huawei.it.ex.one.domain.auth.UserContext;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
class RunAdmissionControlServiceTest {
    @Test
    void rateWindowIsCleanedAfterItExpires() {
        RunAdmissionProperties properties = new RunAdmissionProperties();
        properties.setMaxRunsPerUserPerMinute(1);
        MutableClock clock = new MutableClock(Instant.parse("2026-05-20T00:00:00Z"));
        RunAdmissionControlService service = new RunAdmissionControlService(properties, clock);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        service.acquire(user).close();
        assertThatThrownBy(() -> service.acquire(user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUN_RATE_LIMITED");

        clock.advance(Duration.ofMinutes(2));
        service.cleanupExpiredUserWindows();

        service.acquire(user).close();
    }

    @Test
    void rateLimitUsesGlobalUserIdAsOwnerWhenPresent() {
        RunAdmissionProperties properties = new RunAdmissionProperties();
        properties.setMaxRunsPerUserPerMinute(1);
        RunAdmissionControlService service = new RunAdmissionControlService(
                properties, new MutableClock(Instant.parse("2026-05-20T00:00:00Z")));
        UserContext first = enterpriseUser("tenant1", "same-raw-user", 1001L);
        UserContext second = enterpriseUser("tenant1", "same-raw-user", 1002L);

        service.acquire(first).close();

        service.acquire(second).close();
    }

    private UserContext enterpriseUser(String tenantId, String userId, Long globalUserId) {
        return new UserContext(tenantId, userId, "User One", "account-" + userId,
                "001", "用户一", "INTERNAL", "uuid-" + userId,
                "user-one", "User One", "用户一", globalUserId);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
