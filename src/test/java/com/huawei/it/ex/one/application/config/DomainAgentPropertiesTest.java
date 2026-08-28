package com.huawei.it.ex.one.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

class DomainAgentPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void keepsRefusalAutoSwitchDisabledByDefault() {
        contextRunner.run(context -> {
            DomainAgentProperties properties = context.getBean(DomainAgentProperties.class);

            assertThat(properties.isRefusalAutoSwitchEnabled()).isFalse();
            assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(120));
            assertThat(properties.getStreamIdleTimeout()).isEqualTo(Duration.ofSeconds(300));
            assertThat(properties.getStreamTotalTimeout()).isEqualTo(Duration.ofMinutes(15));
            assertThat(properties.isAsyncTaskEnabled()).isFalse();
            assertThat(properties.getAsyncTaskMaxDuration()).isEqualTo(Duration.ofHours(24));
            assertThat(properties.getAsyncTaskCallbackMaxFrames()).isEqualTo(512);
            assertThat(properties.getAsyncTaskCallbackMaxBytes()).isEqualTo(4 * 1024 * 1024);
            assertThat(properties.getAsyncTaskCallbackMaxConcurrency()).isEqualTo(4);
        });
    }

    @Test
    void rejectsInvalidAsyncTaskLimitsAtStartup() {
        contextRunner.withPropertyValues(
                        "financeex.domain-agent.async-task-callback-max-concurrency=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsRefusalAutoSwitchSetting() {
        contextRunner.withPropertyValues("financeex.domain-agent.refusal-auto-switch-enabled=true")
                .run(context -> assertThat(context.getBean(DomainAgentProperties.class)
                        .isRefusalAutoSwitchEnabled()).isTrue());
    }

    @Test
    void bindsAndNormalizesBindingCompensationRetrySettings() {
        contextRunner.withPropertyValues(
                        "financeex.domain-agent.binding-compensation-max-attempts=4",
                        "financeex.domain-agent.binding-compensation-retry-backoff=2s")
                .run(context -> {
                    DomainAgentProperties properties = context.getBean(DomainAgentProperties.class);

                    assertThat(properties.normalizedBindingCompensationMaxAttempts()).isEqualTo(3);
                    assertThat(properties.normalizedBindingCompensationRetryBackoff())
                            .isEqualTo(Duration.ofSeconds(1));
                });
    }

    @Test
    void applicationYamlUsesLegacyTimeoutAsFallbackForBothStreamTimeouts() throws IOException {
        DomainAgentProperties properties = bindApplicationYaml(Map.of(
                "FINANCEEX_DOMAIN_AGENT_TIMEOUT", "180s"));

        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(180));
        assertThat(properties.getStreamIdleTimeout()).isEqualTo(Duration.ofSeconds(180));
        assertThat(properties.getStreamTotalTimeout()).isEqualTo(Duration.ofSeconds(180));
    }

    @Test
    void applicationYamlPrefersNewStreamTimeoutOverLegacyTimeout() throws IOException {
        DomainAgentProperties properties = bindApplicationYaml(Map.of(
                "FINANCEEX_DOMAIN_AGENT_TIMEOUT", "180s",
                "FINANCEEX_DOMAIN_AGENT_STREAM_IDLE_TIMEOUT", "300s"));

        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(180));
        assertThat(properties.getStreamIdleTimeout()).isEqualTo(Duration.ofSeconds(300));
        assertThat(properties.getStreamTotalTimeout()).isEqualTo(Duration.ofSeconds(180));
    }

    private DomainAgentProperties bindApplicationYaml(Map<String, Object> environmentValues) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-environment", environmentValues));
        new YamlPropertySourceLoader()
                .load("application", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment)
                .bind("financeex.domain-agent", Bindable.of(DomainAgentProperties.class))
                .orElseThrow(() -> new IllegalStateException("financeex.domain-agent was not bound"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DomainAgentProperties.class)
    static class TestConfiguration {
    }
}
