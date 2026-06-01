package com.huawei.finance.front.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

class RelayAgentRuntimeConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(WebClient.Builder.class, WebClient::builder)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(
                    RelayAgentRuntime.class,
                    RelayRuntimeResponseNormalizer.class,
                    RelayStreamHttpRuntimeAdapter.class,
                    RelayWebSocketRuntimeAdapter.class,
                    RelayWebSocketFrameTranslator.class);

    @Test
    void defaultRelayProviderCreatesRuntimeAndProtocolAdapters() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentRuntime.class);
            assertThat(context).hasSingleBean(RelayAgentRuntime.class);
            assertThat(context).hasSingleBean(RelayStreamHttpRuntimeAdapter.class);
            assertThat(context).hasSingleBean(RelayWebSocketRuntimeAdapter.class);
        });
    }

    @Test
    void relayWebSocketApiAdapterStillUsesSingleRelayRuntime() {
        contextRunner
                .withPropertyValues(
                        "financeex.agent-runtime.provider=relay",
                        "financeex.agent-runtime.api-adapter=relay-websocket")
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentRuntime.class);
                    assertThat(context).hasSingleBean(RelayAgentRuntime.class);
                    assertThat(context).hasSingleBean(RelayWebSocketRuntimeAdapter.class);
                });
    }

    @Test
    void nonRelayProviderDoesNotCreateRelayAdapter() {
        contextRunner
                .withPropertyValues("financeex.agent-runtime.provider=custom-runtime")
                .run(context -> assertThat(context).doesNotHaveBean(AgentRuntime.class));
    }
}
