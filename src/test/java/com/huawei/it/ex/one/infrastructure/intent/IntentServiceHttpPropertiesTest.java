package com.huawei.it.ex.one.infrastructure.intent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

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

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IntentServiceHttpProperties.class)
    static class TestConfiguration {
    }
}
