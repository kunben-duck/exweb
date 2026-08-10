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

class RuntimeStreamLimitsPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void usesProductionSafeDefaults() {
        contextRunner.run(context -> {
            RuntimeStreamLimitsProperties properties = context.getBean(RuntimeStreamLimitsProperties.class);

            assertThat(properties.getPendingMaxEventsPerRun()).isEqualTo(512);
            assertThat(properties.pendingMaxBytesPerRun()).isEqualTo(DataSize.ofMegabytes(4).toBytes());
            assertThat(properties.getPendingMaxEventsPerInstance()).isEqualTo(8_192);
            assertThat(properties.pendingMaxBytesPerInstance()).isEqualTo(DataSize.ofMegabytes(64).toBytes());
            assertThat(properties.getAssistantMaxPartsPerRun()).isEqualTo(10_000);
            assertThat(properties.assistantMaxBytesPerRun()).isEqualTo(DataSize.ofMegabytes(16).toBytes());
            assertThat(properties.getAssistantProcessMaxRatio()).isEqualTo(25);
            assertThat(properties.getOverflowCancelTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getStopOwnerHandoffTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getStopFinalizationLease()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.getStopReplayPageSize()).isEqualTo(16);
            assertThat(properties.getStopReplayMaxEventsPerRun()).isEqualTo(10_000);
            assertThat(properties.getStopReplayMaxConcurrency()).isEqualTo(2);
            assertThat(properties.getStopReplayQueryTimeoutSeconds()).isEqualTo(2);
            assertThat(properties.getStopReplayTotalTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void bindsAllThresholdsAndCalculatesProcessBudgets() {
        contextRunner.withPropertyValues(
                        "financeex.agent-runtime.stream-limits.pending-max-events-per-run=10",
                        "financeex.agent-runtime.stream-limits.pending-max-bytes-per-run=1KB",
                        "financeex.agent-runtime.stream-limits.pending-max-events-per-instance=20",
                        "financeex.agent-runtime.stream-limits.pending-max-bytes-per-instance=2KB",
                        "financeex.agent-runtime.stream-limits.assistant-max-parts-per-run=40",
                        "financeex.agent-runtime.stream-limits.assistant-max-bytes-per-run=4KB",
                        "financeex.agent-runtime.stream-limits.assistant-process-max-ratio=25",
                        "financeex.agent-runtime.stream-limits.assistant-max-active-parts-per-instance=80",
                        "financeex.agent-runtime.stream-limits.assistant-max-active-bytes-per-instance=8KB",
                        "financeex.agent-runtime.stream-limits.overflow-cancel-timeout=3s")
                .run(context -> {
                    RuntimeStreamLimitsProperties properties = context.getBean(RuntimeStreamLimitsProperties.class);

                    assertThat(properties.assistantProcessMaxPartsPerRun()).isEqualTo(10);
                    assertThat(properties.assistantProcessMaxBytesPerRun()).isEqualTo(1_024L);
                    assertThat(properties.assistantProcessMaxPartsPerInstance()).isEqualTo(20);
                    assertThat(properties.assistantProcessMaxBytesPerInstance()).isEqualTo(2_048L);
                    assertThat(properties.getOverflowCancelTimeout()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    void rejectsInstanceThresholdBelowRunThreshold() {
        contextRunner.withPropertyValues(
                        "financeex.agent-runtime.stream-limits.pending-max-events-per-run=10",
                        "financeex.agent-runtime.stream-limits.pending-max-events-per-instance=9")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsInvalidProcessRatio() {
        contextRunner.withPropertyValues(
                        "financeex.agent-runtime.stream-limits.assistant-process-max-ratio=101")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsInvalidStopReplayBoundaries() {
        contextRunner.withPropertyValues(
                        "financeex.agent-runtime.stream-limits.stop-replay-page-size=17",
                        "financeex.agent-runtime.stream-limits.stop-replay-max-events-per-run=16")
                .run(context -> assertThat(context).hasFailed());

        contextRunner.withPropertyValues(
                        "financeex.agent-runtime.stream-limits.stop-replay-query-timeout-seconds=6",
                        "financeex.agent-runtime.stream-limits.stop-replay-total-timeout=5s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsFinalizationLeaseNotGreaterThanHandoffTimeout() {
        contextRunner.withPropertyValues(
                        "financeex.agent-runtime.stream-limits.stop-owner-handoff-timeout=2s",
                        "financeex.agent-runtime.stream-limits.stop-finalization-lease=2s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RuntimeStreamLimitsProperties.class)
    static class TestConfiguration {
    }
}
