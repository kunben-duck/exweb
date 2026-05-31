package com.huawei.finance.front.one.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRecoveryPort;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRecoveryRequest;
import com.huawei.finance.front.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.infrastructure.id.GeneratedApplicationInstanceIdProvider;
import com.huawei.finance.front.one.infrastructure.runtime.UnsupportedAgentRuntimeRecoveryPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

class DefaultPortImplementationConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DefaultPortImplementationConfiguration.class);

    @Test
    void createsDefaultPortImplementationsWhenNoCustomBeanExists() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ApplicationInstanceIdProvider.class);
            assertThat(context.getBean(ApplicationInstanceIdProvider.class))
                    .isInstanceOf(GeneratedApplicationInstanceIdProvider.class);
            assertThat(context).hasSingleBean(AgentRuntimeRecoveryPort.class);
            assertThat(context.getBean(AgentRuntimeRecoveryPort.class))
                    .isInstanceOf(UnsupportedAgentRuntimeRecoveryPort.class);
        });
    }

    @Test
    void defaultInstanceIdProviderUsesConfiguredInstanceId() {
        contextRunner
                .withPropertyValues("financeex.instance-id=configured-instance")
                .run(context -> assertThat(context.getBean(ApplicationInstanceIdProvider.class).currentInstanceId())
                        .isEqualTo("configured-instance"));
    }

    @Test
    void customInstanceIdProviderOverridesDefaultPortImplementation() {
        ApplicationInstanceIdProvider custom = () -> "custom-instance";

        contextRunner
                .withBean(ApplicationInstanceIdProvider.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(ApplicationInstanceIdProvider.class);
                    assertThat(context.getBean(ApplicationInstanceIdProvider.class)).isSameAs(custom);
                });
    }

    @Test
    void customRuntimeRecoveryPortOverridesDefaultPortImplementation() {
        AgentRuntimeRecoveryPort custom = new AgentRuntimeRecoveryPort() {
            @Override
            public boolean supports(AgentRuntimeRecoveryRequest request) {
                return true;
            }

            @Override
            public Flux<ChatEvent> recover(AgentRuntimeRecoveryRequest request) {
                return Flux.empty();
            }
        };

        contextRunner
                .withBean(AgentRuntimeRecoveryPort.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentRuntimeRecoveryPort.class);
                    assertThat(context.getBean(AgentRuntimeRecoveryPort.class)).isSameAs(custom);
                });
    }
}
