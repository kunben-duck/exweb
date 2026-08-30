/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

class ChatStreamPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void usesEnabledTripleThresholdDefaults() {
        contextRunner.run(context -> {
            ChatStreamProperties properties = context.getBean(ChatStreamProperties.class);
            assertThat(properties.isEventBatchEnabled()).isTrue();
            assertThat(properties.requiredEventBatchMaxSize()).isEqualTo(16);
            assertThat(properties.requiredEventBatchMaxWait()).isEqualTo(Duration.ofMillis(20));
            assertThat(properties.requiredEventBatchMaxBytes()).isEqualTo(DataSize.ofKilobytes(256).toBytes());
            assertThat(properties.requiredAssistantPartBatchMaxSize()).isEqualTo(100);
            assertThat(properties.requiredAssistantPartBatchMaxBytes()).isEqualTo(DataSize.ofMegabytes(1).toBytes());
        });
    }

    @Test
    void bindsConfiguredTripleThresholds() {
        contextRunner.withPropertyValues(
                        "financeex.chat-stream.event-batch-enabled=false",
                        "financeex.chat-stream.event-batch-max-size=8",
                        "financeex.chat-stream.event-batch-max-wait=40ms",
                        "financeex.chat-stream.event-batch-max-bytes=64KB",
                        "financeex.chat-stream.assistant-part-batch-max-size=25",
                        "financeex.chat-stream.assistant-part-batch-max-bytes=128KB")
                .run(context -> {
                    ChatStreamProperties properties = context.getBean(ChatStreamProperties.class);
                    assertThat(properties.isEventBatchEnabled()).isFalse();
                    assertThat(properties.requiredEventBatchMaxSize()).isEqualTo(8);
                    assertThat(properties.requiredEventBatchMaxWait()).isEqualTo(Duration.ofMillis(40));
                    assertThat(properties.requiredEventBatchMaxBytes()).isEqualTo(DataSize.ofKilobytes(64).toBytes());
                    assertThat(properties.requiredAssistantPartBatchMaxSize()).isEqualTo(25);
                    assertThat(properties.requiredAssistantPartBatchMaxBytes())
                            .isEqualTo(DataSize.ofKilobytes(128).toBytes());
                });
    }

    @Test
    void rejectsNonPositiveBatchThresholdsAtStartup() {
        contextRunner.withPropertyValues("financeex.chat-stream.event-batch-max-size=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("event batch thresholds");
                });
    }

    @Test
    void rejectsNonPositiveAssistantPartBatchThresholdsAtStartup() {
        contextRunner.withPropertyValues("financeex.chat-stream.assistant-part-batch-max-bytes=0B")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("assistant part batch thresholds");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChatStreamProperties.class)
    static class TestConfiguration {
    }
}
