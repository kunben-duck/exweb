package com.huawei.it.ex.one.intent.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.intent.application.config.IntentFailureStrategy;
import com.huawei.it.ex.one.intent.application.config.RouteSignalProperties;
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
    void acceptsSupportedIntentFailureStrategiesAndTrimsValue() {
        contextRunner
                .withPropertyValues("financeex.intent.failure-strategy=RELAY_FALLBACK")
                .run(context -> assertThat(context.getBean(RouteSignalProperties.class)
                        .intentFailureStrategy()).isEqualTo(IntentFailureStrategy.RELAY_FALLBACK));

        contextRunner
                .withPropertyValues("financeex.intent.failure-strategy=  FAIL_RUN  ")
                .run(context -> assertThat(context.getBean(RouteSignalProperties.class)
                        .intentFailureStrategy()).isEqualTo(IntentFailureStrategy.FAIL_RUN));
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
