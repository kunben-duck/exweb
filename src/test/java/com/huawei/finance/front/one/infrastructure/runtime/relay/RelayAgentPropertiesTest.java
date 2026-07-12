package com.huawei.finance.front.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.config.AgentRuntimeForwardCookieProperties;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class RelayAgentPropertiesTest {
    @Test
    void relayPropertiesContainOnlyWebSocketTransportDefaults() {
        RelayAgentProperties properties = new RelayAgentProperties();

        assertThat(properties.getRelay().isEnabled()).isTrue();
        assertThat(properties.getRelay().getWebsocket().getUrl()).isBlank();
        assertThat(properties.getRelay().getWebsocket().getMaxFrameBytes()).isEqualTo(DataSize.ofMegabytes(1));
    }

    @Test
    void forwardCookieDefaultsToEnabledWithBoundedLength() {
        AgentRuntimeForwardCookieProperties properties = new AgentRuntimeForwardCookieProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.normalizedMaxLength()).isEqualTo(8192);
    }
}
