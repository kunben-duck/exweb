package com.huawei.finance.front.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RelayAgentPropertiesTest {
    @Test
    void selectedApiAdapterDefaultsToRelayStreamHttp() {
        RelayAgentProperties properties = new RelayAgentProperties();

        assertThat(properties.selectedApiAdapter()).isEqualTo("relay-stream-http");
    }

    @Test
    void selectedApiAdapterPrefersExplicitApiAdapter() {
        RelayAgentProperties properties = new RelayAgentProperties();
        properties.setApiAdapter("deepseek-chat-completions");

        assertThat(properties.selectedApiAdapter()).isEqualTo("deepseek-chat-completions");
    }

    @Test
    void selectedApiAdapterFallsBackToDefaultWhenBlank() {
        RelayAgentProperties properties = new RelayAgentProperties();
        properties.setApiAdapter(" ");

        assertThat(properties.selectedApiAdapter()).isEqualTo("relay-stream-http");
    }
}
