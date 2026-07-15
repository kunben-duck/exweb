package com.huawei.it.ex.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.application.config.AgentRuntimeForwardCookieProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RelayAgentRuntimeConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(
                    RelayAgentRuntime.class,
                    RelayRuntimeResponseNormalizer.class,
                    RelayWebSocketRuntimeAdapter.class);

    @Test
    void enabledRelayProviderCreatesSingleWebSocketRuntime() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentRuntime.class);
            assertThat(context).hasSingleBean(RelayAgentRuntime.class);
            assertThat(context).hasSingleBean(RelayRuntimeProtocolAdapter.class);
            assertThat(context).hasSingleBean(RelayWebSocketRuntimeAdapter.class);
        });
    }

    @Test
    void disabledRelayProviderDoesNotCreateRuntimeOrTransport() {
        contextRunner
                .withPropertyValues("financeex.agent-runtime.relay.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AgentRuntime.class);
                    assertThat(context).doesNotHaveBean(RelayRuntimeProtocolAdapter.class);
                    assertThat(context).doesNotHaveBean(RelayWebSocketRuntimeAdapter.class);
                });
    }

    @Test
    void relayWebSocketCanReceiveForwardedCookieByDefault() {
        assertThat(new AgentRuntimeForwardCookieProperties().isEnabled()).isTrue();
    }

    @Test
    void relayWebSocketAdapterLoadsWithShortConnectionDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RelayWebSocketRuntimeAdapter.class);
        });
    }
}
