package com.huawei.it.ex.one.application.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.agent.AgentModeAdapter;
import com.huawei.it.ex.one.application.integration.agent.AgentModeOutboundParameters;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.AgentModeSelection;
import com.huawei.it.ex.one.infrastructure.runtime.NoopAgentModeAdapter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentModeAdapterRegistryTest {
    @Test
    void noOpAdapterDoesNotChangeRuntimeMetadataOrRelayConfig() {
        AgentModeAdapterRegistry registry = new AgentModeAdapterRegistry(List.of(new NoopAgentModeAdapter()));
        AgentModeProfile profile = new AgentModeProfile(List.of(
                new AgentModeSelection("thinking", "deep", "深度思考")));

        AgentModeOutboundParameters result = registry.adapt(profile, "relay", null);

        assertThat(result.requestMetadata()).isEmpty();
        assertThat(result.relayConfig()).isEmpty();
        assertThat(result.mergeRequestMetadata(Map.of("scene", "fund")))
                .containsExactlyEntriesOf(Map.of("scene", "fund"));
    }

    @Test
    void providerAdapterCanMapFutureModesAndOverridesClientMetadata() {
        AgentModeAdapter adapter = new AgentModeAdapter() {
            @Override
            public boolean supports(String provider) {
                return "domain-agent".equals(provider);
            }

            @Override
            public AgentModeOutboundParameters adapt(
                    AgentModeProfile profile, String provider, String targetId) {
                return new AgentModeOutboundParameters(Map.of("isThinking", 1), Map.of());
            }
        };
        AgentModeAdapterRegistry registry = new AgentModeAdapterRegistry(
                List.of(new NoopAgentModeAdapter(), adapter));
        AgentModeProfile profile = new AgentModeProfile(List.of(
                new AgentModeSelection("thinking", "deep", null)));

        AgentModeOutboundParameters result = registry.adapt(profile, "domain-agent", "fund-agent");

        assertThat(result.mergeRequestMetadata(Map.of("isThinking", 0, "scene", "fund")))
                .containsEntry("isThinking", 1)
                .containsEntry("scene", "fund");
    }
}
