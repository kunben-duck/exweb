/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.service.routing.IntentFeedbackApplicationService;
import com.huawei.it.ex.one.application.service.routing.IntentFeedbackCommitService;
import com.huawei.it.ex.one.application.service.routing.IntentFeedbackTaskDispatcher;
import com.huawei.it.ex.one.domain.auth.UserContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;

class IntentFeedbackPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(IntentFeedbackExecutorConfiguration.class);

    @Test
    void defaultsCreateDedicatedFixedBoundedExecutor() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            IntentFeedbackProperties properties = context.getBean(IntentFeedbackProperties.class);
            assertThat(properties.getQueueWaitTimeout()).isEqualTo(Duration.ofMillis(500));
            ThreadPoolTaskExecutor executor = context.getBean("intentFeedbackExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getQueueCapacity()).isEqualTo(16);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("finex-intent-feedback-");
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        });
    }

    @Test
    void bindsOverrides() {
        runner.withPropertyValues("financeex.intent.feedback.worker-count=2",
                "financeex.intent.feedback.queue-capacity=8", "financeex.intent.feedback.queue-wait-timeout=100ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolTaskExecutor executor = context.getBean("intentFeedbackExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(2);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(2);
                    assertThat(executor.getQueueCapacity()).isEqualTo(8);
                    assertThat(context.getBean(IntentFeedbackProperties.class).getQueueWaitTimeout())
                            .isEqualTo(Duration.ofMillis(100));
                });
    }

    @Test
    void serviceWiringUsesFeedbackExecutorWithPreferenceExecutorsPresent() {
        IntentFeedbackCommitService commits = mock(IntentFeedbackCommitService.class);
        UserContext user = new UserContext("t", "u", "u");
        when(commits.find(user, "run")).thenAnswer(call -> {
            assertThat(Thread.currentThread().getName()).startsWith("finex-intent-feedback-");
            return Optional.empty();
        });
        runner.withUserConfiguration(IntentPreferenceExecutorConfiguration.class,
                        IntentFeedbackTaskDispatcher.class, IntentFeedbackApplicationService.class)
                .withBean(RouteMemoryProperties.class, RouteMemoryProperties::new)
                .withBean(IntentFeedbackCommitService.class, () -> commits)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("intentPreferenceWriteExecutor").hasBean("intentPreferenceReadExecutor");
                    assertThat(context.getBean(IntentFeedbackApplicationService.class)
                            .find(user, "run").block(Duration.ofSeconds(2))).isEmpty();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker-count=0", "worker-count=-1", "queue-capacity=0", "queue-capacity=-1",
            "queue-wait-timeout=0ms", "queue-wait-timeout=-1ms", "queue-wait-timeout=999999999999s"})
    void invalidSettingsFailStartup(String setting) {
        runner.withPropertyValues("financeex.intent.feedback." + setting)
                .run(context -> assertThat(context).hasFailed());
    }
}
