package com.huawei.it.ex.one.infrastructure.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ShortTermMemoryStoragePropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void usesTwoSecondDatabaseQueryTimeoutByDefault() {
        contextRunner.run(context -> assertThat(context.getBean(ShortTermMemoryStorageProperties.class)
                .getDatabaseQueryTimeoutSeconds()).isEqualTo(2));
    }

    @Test
    void bindsConfiguredDatabaseQueryTimeout() {
        contextRunner.withPropertyValues(
                        "financeex.memory.short-term.storage.database-query-timeout-seconds=5")
                .run(context -> assertThat(context.getBean(ShortTermMemoryStorageProperties.class)
                        .getDatabaseQueryTimeoutSeconds()).isEqualTo(5));
    }

    @Test
    void rejectsDatabaseQueryTimeoutOutsideSupportedRange() {
        contextRunner.withPropertyValues(
                        "financeex.memory.short-term.storage.database-query-timeout-seconds=0")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                        "financeex.memory.short-term.storage.database-query-timeout-seconds=31")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ShortTermMemoryStorageProperties.class)
    static class TestConfiguration {
    }
}
