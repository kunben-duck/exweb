/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.huawei.it.ex.one.application.integration.observation.ObservationRepository;
import com.huawei.it.ex.one.application.service.observation.ObservationWriter;
import com.huawei.it.ex.one.application.service.observation.TurnTraceCoordinator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class ObservabilityConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(ObservabilityConfiguration.class)
            .withBean(ObservationRepository.class, () -> mock(ObservationRepository.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void disabledByDefaultAndWriterHasNoExecutor() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TurnTraceCoordinator.class).enabled()).isFalse();
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(
                    context.getBean(ObservationWriter.class), "executor")).isNull();
        });
    }

    @Test
    void positiveConfigurationBindsAndInvalidValuesFailStartup() {
        runner.withPropertyValues("financeex.observability.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TurnTraceCoordinator.class).enabled()).isTrue();
            assertThat(context.getBean(ObservabilityProperties.class).getWriter().getQueueCapacity()).isEqualTo(1024);
        });
        for (String value : new String[]{"worker-count=0", "queue-capacity=0", "queue-wait-timeout=0s",
                "transaction-timeout=-1s"}) {
            runner.withPropertyValues("financeex.observability.writer." + value)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
