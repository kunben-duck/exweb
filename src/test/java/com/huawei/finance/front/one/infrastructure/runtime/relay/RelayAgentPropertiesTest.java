package com.huawei.finance.front.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.config.AgentRuntimeForwardCookieProperties;
import org.junit.jupiter.api.Test;

class RelayAgentPropertiesTest {
    @Test
    void relayPropertiesDefaultToStreamHttpEndpoint() {
        RelayAgentProperties properties = new RelayAgentProperties();

        assertThat(properties.getStreamPath()).isEqualTo("/v1/agent/runs/stream");
        assertThat(properties.getStopPath()).isEqualTo("/v1/agent/runs/{runId}/stop");
    }

    @Test
    void forwardCookieOnlyAllowsConfiguredRelayAdapters() {
        AgentRuntimeForwardCookieProperties properties = new AgentRuntimeForwardCookieProperties();

        assertThat(properties.isAdapterAllowed("relay-stream-http")).isTrue();
        assertThat(properties.isAdapterAllowed("third-party-adapter")).isFalse();
    }
}
