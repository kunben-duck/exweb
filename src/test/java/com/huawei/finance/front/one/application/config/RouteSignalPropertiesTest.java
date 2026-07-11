package com.huawei.finance.front.one.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RouteSignalPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RouteSignalProperties.class);

    @Test
    void defaultsIntentFailureStrategyToRelayFallback() {
        contextRunner.run(context -> assertThat(context.getBean(RouteSignalProperties.class)
                .intentFailureStrategy()).isEqualTo(IntentFailureStrategy.RELAY_FALLBACK));
    }

    @Test
    void invalidIntentFailureStrategyFailsFast() {
        contextRunner
                .withPropertyValues("financeex.intent.failure-strategy=UNKNOWN")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("financeex.intent.failure-strategy")
                            .hasMessageContaining("UNKNOWN");
                });
    }
}
