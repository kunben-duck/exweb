package com.huawei.it.ex.one.application.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class RuntimeStreamLimitsConfigurationValidatorTest {
    @Test
    void acceptsFinalizationLeaseLongerThanTerminalTransactionTimeout() {
        RuntimeStreamLimitsProperties properties = new RuntimeStreamLimitsProperties();
        properties.setStopFinalizationLease(Duration.ofSeconds(15));

        assertThatCode(() -> new RuntimeStreamLimitsConfigurationValidator(properties, 10)
                .afterSingletonsInstantiated()).doesNotThrowAnyException();
    }

    @Test
    void rejectsFinalizationLeaseThatCannotCoverTerminalTransactionTimeout() {
        RuntimeStreamLimitsProperties properties = new RuntimeStreamLimitsProperties();
        properties.setStopFinalizationLease(Duration.ofSeconds(10));

        assertThatThrownBy(() -> new RuntimeStreamLimitsConfigurationValidator(properties, 10)
                .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stop-finalization-lease");
    }
}
