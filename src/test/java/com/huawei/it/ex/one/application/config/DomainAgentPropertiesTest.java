package com.huawei.it.ex.one.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

class DomainAgentPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void keepsRefusalAutoSwitchDisabledByDefault() {
        contextRunner.run(context -> assertThat(context.getBean(DomainAgentProperties.class)
                .isRefusalAutoSwitchEnabled()).isFalse());
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

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DomainAgentProperties.class)
    static class TestConfiguration {
    }
}
