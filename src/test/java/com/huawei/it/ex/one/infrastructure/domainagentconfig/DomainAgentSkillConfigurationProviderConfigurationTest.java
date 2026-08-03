package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationProvider;

import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

class DomainAgentSkillConfigurationProviderConfigurationTest {
    private static final String ENABLED = "financeex.agent-data-persistence.enabled=true";
    private static final String BASE_URL =
            "financeex.domain-agent-skill-config.base-url=https://skill-config.example.test";
    private static final String QUERY_PATH =
            "financeex.domain-agent-skill-config.query-path=/skill-config";

    @Test
    void createsDefaultHttpProviderWhenFeatureIsDisabled() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DomainAgentSkillConfigurationProvider.class);
            assertThat(context.getBean(DomainAgentSkillConfigurationProvider.class))
                    .isInstanceOf(DefaultDomainAgentSkillConfigurationProvider.class);
        });
    }

    @Test
    void customProviderOverridesDefaultWithoutRequiringHttpConfiguration() {
        DomainAgentSkillConfigurationProvider custom = query -> Mono.just(
                DomainAgentSkillConfiguration.unconfigured(query.skillId()));

        contextRunner()
                .withPropertyValues(ENABLED)
                .withBean(DomainAgentSkillConfigurationProvider.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DomainAgentSkillConfigurationProvider.class);
                    assertThat(context.getBean(DomainAgentSkillConfigurationProvider.class))
                            .isSameAs(custom);
                });
    }

    @Test
    void enabledDefaultProviderUsesDefaultTwoSecondTimeout() {
        contextRunner()
                .withPropertyValues(ENABLED, BASE_URL, QUERY_PATH)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DomainAgentSkillConfigurationProvider.class))
                            .isInstanceOf(DefaultDomainAgentSkillConfigurationProvider.class);
                    assertThat(context.getBean(DomainAgentSkillConfigurationProperties.class)
                            .normalizedTimeout()).isEqualTo(java.time.Duration.ofSeconds(2));
                });
    }

    @Test
    void enabledDefaultProviderRequiresBaseUrl() {
        contextRunner()
                .withPropertyValues(ENABLED, QUERY_PATH)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "financeex.domain-agent-skill-config.base-url must be explicitly configured "
                                            + "when agent data persistence is enabled");
                });
    }

    @Test
    void enabledDefaultProviderRequiresQueryPath() {
        contextRunner()
                .withPropertyValues(ENABLED, BASE_URL)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "financeex.domain-agent-skill-config.query-path must be explicitly configured "
                                            + "when agent data persistence is enabled");
                });
    }

    @Test
    void enabledDefaultProviderRejectsInvalidTimeoutAndEndpoint() {
        contextRunner()
                .withPropertyValues(ENABLED, BASE_URL, QUERY_PATH,
                        "financeex.domain-agent-skill-config.timeout=0s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner()
                .withPropertyValues(ENABLED,
                        "financeex.domain-agent-skill-config.base-url=file:///tmp/config",
                        QUERY_PATH)
                .run(context -> assertThat(context).hasFailed());
        contextRunner()
                .withPropertyValues(ENABLED, BASE_URL,
                        "financeex.domain-agent-skill-config.query-path=relative/path")
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DomainAgentSkillConfigurationProviderConfiguration.class)
                .withBean(AgentDataPersistenceProperties.class, AgentDataPersistenceProperties::new)
                .withBean(WebClient.Builder.class, WebClient::builder);
    }
}
