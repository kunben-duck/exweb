package com.huawei.it.ex.one.application.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.huawei.it.ex.one.application.config.RuntimeStreamLimitsProperties;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class RuntimePendingBudgetRegistryTest {

    @Test
    void enforcesRunAndInstanceEventLimitsAndReleasesReservations() {
        RuntimeStreamLimitsProperties properties = properties(2, 3, 1_024, 2_048);
        RuntimePendingBudgetRegistry registry = new RuntimePendingBudgetRegistry(properties);
        RuntimePendingBudgetRegistry.Reservation first = registry.reserve("run-a", 10L);
        RuntimePendingBudgetRegistry.Reservation second = registry.reserve("run-a", 10L);

        RuntimeStreamLimitExceededException runFailure = catchThrowableOfType(
                RuntimeStreamLimitExceededException.class,
                () -> registry.reserve("run-a", 10L));
        assertThat(runFailure.limitType()).isEqualTo(RuntimeStreamLimitType.PENDING_EVENTS);

        RuntimePendingBudgetRegistry.Reservation third = registry.reserve("run-b", 10L);
        RuntimeStreamLimitExceededException instanceFailure = catchThrowableOfType(
                RuntimeStreamLimitExceededException.class,
                () -> registry.reserve("run-b", 10L));
        assertThat(instanceFailure.limitType()).isEqualTo(RuntimeStreamLimitType.PENDING_INSTANCE_EVENTS);

        first.close();
        second.close();
        third.close();
        assertThat(registry.instanceEvents()).isZero();
        assertThat(registry.instanceBytes()).isZero();
    }

    @Test
    void enforcesByteLimitsAndReleaseRunIsIdempotent() {
        RuntimeStreamLimitsProperties properties = properties(10, 20, 10, 15);
        RuntimePendingBudgetRegistry registry = new RuntimePendingBudgetRegistry(properties);
        RuntimePendingBudgetRegistry.Reservation first = registry.reserve("run-a", 8L);

        RuntimeStreamLimitExceededException runFailure = catchThrowableOfType(
                RuntimeStreamLimitExceededException.class,
                () -> registry.reserve("run-a", 3L));
        assertThat(runFailure.limitType()).isEqualTo(RuntimeStreamLimitType.PENDING_BYTES);

        RuntimePendingBudgetRegistry.Reservation second = registry.reserve("run-b", 7L);
        RuntimeStreamLimitExceededException instanceFailure = catchThrowableOfType(
                RuntimeStreamLimitExceededException.class,
                () -> registry.reserve("run-b", 1L));
        assertThat(instanceFailure.limitType()).isEqualTo(RuntimeStreamLimitType.PENDING_INSTANCE_BYTES);

        registry.releaseRun("run-a");
        registry.releaseRun("run-a");
        first.close();
        second.close();
        assertThat(registry.instanceEvents()).isZero();
        assertThat(registry.instanceBytes()).isZero();
    }

    private RuntimeStreamLimitsProperties properties(int runEvents,
                                                      int instanceEvents,
                                                      long runBytes,
                                                      long instanceBytes) {
        RuntimeStreamLimitsProperties properties = new RuntimeStreamLimitsProperties();
        properties.setPendingMaxEventsPerRun(runEvents);
        properties.setPendingMaxEventsPerInstance(instanceEvents);
        properties.setPendingMaxBytesPerRun(DataSize.ofBytes(runBytes));
        properties.setPendingMaxBytesPerInstance(DataSize.ofBytes(instanceBytes));
        return properties;
    }
}
