package com.huawei.it.ex.one.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.routing.RelayOutputMode;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RuntimeProfile;

import org.junit.jupiter.api.Test;

import java.util.Map;

class RelayOutputModeMetadataTest {
    @Test
    void answerOnlyRouteProducesTrustedRunMarker() {
        RouteTarget route = RouteTarget.agentRuntimeAnswerStreamOnly(
                "intent-agent", 1.0, "sensitive information");

        assertThat(RelayOutputModeMetadata.fromRoute(route))
                .isEqualTo(RelayOutputMode.ANSWER_STREAM_ONLY);
        assertThat(RelayOutputModeMetadata.runMetadataOverlay(route))
                .containsExactlyEntriesOf(Map.of(RelayOutputModeMetadata.RUN_METADATA_KEY, true));
    }

    @Test
    void missingOrMalformedMarkerDefaultsToFullStream() {
        assertThat(RelayOutputModeMetadata.fromRunMetadata(Map.of()))
                .isEqualTo(RelayOutputMode.FULL_STREAM);
        assertThat(RelayOutputModeMetadata.fromRunMetadata(Map.of(
                RelayOutputModeMetadata.RUN_METADATA_KEY, "true")))
                .isEqualTo(RelayOutputMode.FULL_STREAM);
        assertThat(RelayOutputModeMetadata.fromRunMetadata(Map.of(
                RelayOutputModeMetadata.RUN_METADATA_KEY, false)))
                .isEqualTo(RelayOutputMode.FULL_STREAM);
    }

    @Test
    void privateMarkerIsRemovedWithoutChangingBusinessMetadata() {
        Map<String, Object> sanitized = RelayOutputModeMetadata.removePrivateRunMetadata(Map.of(
                "scene", "finance",
                RelayOutputModeMetadata.RUN_METADATA_KEY, true));

        assertThat(sanitized).containsExactlyEntriesOf(Map.of("scene", "finance"));
    }

    @Test
    void nonRelayAndDomainExpertRoutesCannotEnableAnswerOnlyMode() {
        RouteTarget domainAgent = new RouteTarget(
                com.huawei.it.ex.one.domain.routing.RouteType.DOMAIN_AGENT,
                "skill-1", "intent-agent", 1.0, "domain", RuntimeProfile.DELEGATE,
                null, RelayOutputMode.ANSWER_STREAM_ONLY);
        RouteTarget expert = new RouteTarget(
                com.huawei.it.ex.one.domain.routing.RouteType.AGENT_RUNTIME,
                null, "intent-agent", 1.0, "expert", RuntimeProfile.DOMAIN_EXPERT,
                "system-awareness", RelayOutputMode.ANSWER_STREAM_ONLY);

        assertThat(domainAgent.relayOutputMode()).isEqualTo(RelayOutputMode.FULL_STREAM);
        assertThat(expert.relayOutputMode()).isEqualTo(RelayOutputMode.FULL_STREAM);
    }
}
