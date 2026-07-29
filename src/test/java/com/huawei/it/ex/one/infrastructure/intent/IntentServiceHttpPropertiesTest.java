package com.huawei.it.ex.one.infrastructure.intent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

class IntentServiceHttpPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void defaultsNoMatchAgentName() {
        contextRunner.run(context -> assertThat(context.getBean(IntentServiceHttpProperties.class)
                .normalizedNoMatchAgentName()).isEqualTo("FIN Supervisor Agent"));
    }

    @Test
    void bindsAndTrimsConfiguredNoMatchAgentName() {
        contextRunner.withPropertyValues("financeex.intent.no-match-agent-name=  财务总控 Agent  ")
                .run(context -> assertThat(context.getBean(IntentServiceHttpProperties.class)
                        .normalizedNoMatchAgentName()).isEqualTo("财务总控 Agent"));
    }

    @Test
    void defaultsToBlockingInvocationAndStreamTimeouts() {
        contextRunner.run(context -> {
            IntentServiceHttpProperties properties = context.getBean(IntentServiceHttpProperties.class);

            assertThat(properties.getInvocationMode()).isEqualTo(IntentInvocationMode.BLOCKING);
            assertThat(properties.getRecognizePath())
                    .isEqualTo("/intent-recognition-configuration/getIntentDecision");
            assertThat(properties.getRecognizeStreamPath())
                    .isEqualTo("/intent-recognition-configuration/getIntentDecisionStream");
            assertThat(properties.normalizedStreamFirstEventTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.normalizedStreamIdleTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.normalizedStreamTotalTimeout()).isEqualTo(Duration.ofSeconds(120));
        });
    }

    @Test
    void bindsStreamingInvocationConfiguration() {
        contextRunner.withPropertyValues(
                        "financeex.intent.invocation-mode=STREAMING",
                        "financeex.intent.recognize-stream-path=/stream",
                        "financeex.intent.stream-first-event-timeout=2s",
                        "financeex.intent.stream-idle-timeout=12s",
                        "financeex.intent.stream-total-timeout=90s")
                .run(context -> {
                    IntentServiceHttpProperties properties = context.getBean(IntentServiceHttpProperties.class);

                    assertThat(properties.getInvocationMode()).isEqualTo(IntentInvocationMode.STREAMING);
                    assertThat(properties.getRecognizeStreamPath()).isEqualTo("/stream");
                    assertThat(properties.normalizedStreamFirstEventTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.normalizedStreamIdleTimeout()).isEqualTo(Duration.ofSeconds(12));
                    assertThat(properties.normalizedStreamTotalTimeout()).isEqualTo(Duration.ofSeconds(90));
                });
    }

    @Test
    void rejectsUnknownInvocationMode() {
        contextRunner.withPropertyValues("financeex.intent.invocation-mode=AUTO")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "No enum constant com.huawei.it.ex.one.infrastructure.intent."
                                            + "IntentInvocationMode.AUTO");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IntentServiceHttpProperties.class)
    static class TestConfiguration {
    }
}
