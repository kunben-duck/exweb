package com.huawei.finance.front.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.AgentRuntimeForwardCookieProperties;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import com.huawei.finance.front.one.application.integration.identity.ApplicationInstanceIdProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

class RelayAgentRuntimeConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(WebClient.Builder.class, WebClient::builder)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ApplicationInstanceIdProvider.class, () -> () -> "instance-test")
            .withUserConfiguration(
                    RelayAgentRuntime.class,
                    RelayRuntimeResponseNormalizer.class,
                    RelayStreamHttpRuntimeAdapter.class,
                    RelayWebSocketRuntimeAdapter.class);

    @Test
    void defaultRelayProviderCreatesRuntimeAndStreamHttpAdapter() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentRuntime.class);
            assertThat(context).hasSingleBean(RelayAgentRuntime.class);
            assertThat(context).hasSingleBean(RelayStreamHttpRuntimeAdapter.class);
            assertThat(context).hasSingleBean(RelayWebSocketRuntimeAdapter.class);
        });
    }

    @Test
    void configuredRelayWebSocketAdapterCreatesRuntime() {
        contextRunner
                .withPropertyValues("financeex.agent-runtime.relay.adapter=relay-websocket")
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentRuntime.class);
                    assertThat(context).hasSingleBean(RelayAgentRuntime.class);
                });
    }

    @Test
    void unknownConfiguredAdapterFailsFastAtStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(RelayAgentRuntime.class)
                .withPropertyValues("financeex.agent-runtime.provider=relay")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Relay adapter 'relay-stream-http' is not registered. Registered adapters: []");
                });
    }

    @Test
    void invalidConfiguredAdapterNameReportsRegisteredAdapters() {
        contextRunner
                .withPropertyValues("financeex.agent-runtime.relay.adapter=relay-missing")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("Relay adapter 'relay-missing' is not registered")
                            .hasMessageContaining("relay-stream-http")
                            .hasMessageContaining("relay-websocket");
                });
    }

    @Test
    void nonRelayProviderDoesNotCreateRelayAdapter() {
        contextRunner
                .withPropertyValues("financeex.agent-runtime.provider=custom-runtime")
                .run(context -> assertThat(context).doesNotHaveBean(AgentRuntime.class));
    }

    @Test
    void relayWebSocketIsAllowedToReceiveForwardedCookieByDefault() {
        assertThat(new AgentRuntimeForwardCookieProperties().isAdapterAllowed("relay-websocket")).isTrue();
    }

    @Test
    void invalidRelayWebSocketConnectionModeFailsFastAtStartup() {
        contextRunner
                .withPropertyValues(
                        "financeex.agent-runtime.relay.adapter=relay-websocket",
                        "financeex.agent-runtime.relay.websocket.connection-mode=unknown")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("Unsupported Relay WebSocket connection-mode");
                });
    }
}
