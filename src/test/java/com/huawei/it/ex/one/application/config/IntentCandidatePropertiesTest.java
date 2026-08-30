/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

class IntentCandidatePropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsDefaultsAndOverrides() {
        contextRunner.run(context -> {
            IntentCandidateProperties properties = context.getBean(IntentCandidateProperties.class);
            assertThat(properties.getMaxConcurrency()).isEqualTo(8);
            assertThat(properties.getAuthIoMaxSize()).isEqualTo(2);
            assertThat(properties.getAuthIoQueueCapacity()).isEqualTo(16);
            assertThat(properties.getRetryMinBackoff()).isEqualTo(Duration.ofMillis(200));
            assertThat(properties.getRetryMaxBackoff()).isEqualTo(Duration.ofSeconds(1));
        });

        contextRunner.withPropertyValues(
                        "financeex.intent.candidate.max-concurrency=12",
                        "financeex.intent.candidate.auth-io-max-size=3",
                        "financeex.intent.candidate.auth-io-queue-capacity=24",
                        "financeex.intent.candidate.retry-min-backoff=50ms",
                        "financeex.intent.candidate.retry-max-backoff=500ms")
                .run(context -> {
                    IntentCandidateProperties properties = context.getBean(IntentCandidateProperties.class);
                    assertThat(properties.getMaxConcurrency()).isEqualTo(12);
                    assertThat(properties.getAuthIoMaxSize()).isEqualTo(3);
                    assertThat(properties.getAuthIoQueueCapacity()).isEqualTo(24);
                    assertThat(properties.getRetryMinBackoff()).isEqualTo(Duration.ofMillis(50));
                    assertThat(properties.getRetryMaxBackoff()).isEqualTo(Duration.ofMillis(500));
                });
    }

    @Test
    void rejectsInvalidConcurrencyAndBackoff() {
        contextRunner.withPropertyValues(
                        "financeex.intent.candidate.max-concurrency=1",
                        "financeex.intent.candidate.auth-io-max-size=2")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                        "financeex.intent.candidate.retry-min-backoff=2s",
                        "financeex.intent.candidate.retry-max-backoff=1s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("financeex.intent.candidate.retry-min-backoff=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IntentCandidateProperties.class)
    static class TestConfiguration {
    }
}
