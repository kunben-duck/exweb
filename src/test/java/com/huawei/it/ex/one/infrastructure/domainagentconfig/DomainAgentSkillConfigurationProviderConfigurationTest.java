package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationProvider;

import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

class DomainAgentSkillConfigurationProviderConfigurationTest {
    private static final String ENABLED = "financeex.agent-data-persistence.enabled=true";
    private static final String TIMEOUT = "financeex.domain-agent-skill-config.timeout=2s";

    @Test
    void createsDefaultProviderAndPlaceholderClientWhenFeatureIsDisabled() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DomainAgentSkillConfigurationProvider.class);
            assertThat(context.getBean(DomainAgentSkillConfigurationProvider.class))
                    .isInstanceOf(DefaultDomainAgentSkillConfigurationProvider.class);
            assertThat(context).hasSingleBean(DomainAgentSkillConfigurationClient.class);
            assertThat(context.getBean(DomainAgentSkillConfigurationClient.class))
                    .isInstanceOf(DefaultDomainAgentSkillConfigurationClient.class);
        });
    }

    @Test
    void customProviderOverridesDefaultWithoutRequiringClientOrTimeout() {
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
    void enabledDefaultProviderStartsWhenTimeoutIsConfigured() {
        contextRunner()
                .withPropertyValues(ENABLED, TIMEOUT)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DomainAgentSkillConfigurationProvider.class))
                            .isInstanceOf(DefaultDomainAgentSkillConfigurationProvider.class);
                    assertThat(context.getBean(DomainAgentSkillConfigurationClient.class))
                            .isInstanceOf(DefaultDomainAgentSkillConfigurationClient.class);
                });
    }

    @Test
    void enabledDefaultProviderRequiresExplicitTimeout() {
        contextRunner()
                .withPropertyValues(ENABLED)
                .withBean(DomainAgentSkillConfigurationClient.class,
                        DomainAgentSkillConfigurationProviderConfigurationTest::configuredClient)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "financeex.domain-agent-skill-config.timeout must be explicitly configured "
                                            + "when agent data persistence is enabled");
                });
    }

    @Test
    void enabledDefaultProviderRejectsInvalidTimeout() {
        contextRunner()
                .withPropertyValues(ENABLED, "financeex.domain-agent-skill-config.timeout=0s")
                .withBean(DomainAgentSkillConfigurationClient.class,
                        DomainAgentSkillConfigurationProviderConfigurationTest::configuredClient)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void customClientOverridesPlaceholderAndStartsDefaultProvider() {
        DomainAgentSkillConfigurationClient custom = configuredClient();

        contextRunner()
                .withPropertyValues(ENABLED, TIMEOUT)
                .withBean(DomainAgentSkillConfigurationClient.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DomainAgentSkillConfigurationClient.class);
                    assertThat(context.getBean(DomainAgentSkillConfigurationClient.class))
                            .isSameAs(custom);
                    assertThat(context.getBean(DomainAgentSkillConfigurationProvider.class))
                            .isInstanceOf(DefaultDomainAgentSkillConfigurationProvider.class);
                });
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DomainAgentSkillConfigurationProviderConfiguration.class)
                .withBean(AgentDataPersistenceProperties.class, AgentDataPersistenceProperties::new);
    }

    private static DomainAgentSkillConfigurationClient configuredClient() {
        return skillIds -> new SkillConfigurationResponse(
                "success",
                List.of(new SkillConfigurationItem(skillIds.getFirst(), "Y")));
    }
}
