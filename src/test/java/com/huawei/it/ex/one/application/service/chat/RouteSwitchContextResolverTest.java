package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.routing.RouteType;
import com.huawei.it.ex.one.domain.routing.RuntimeProfile;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

class RouteSwitchContextResolverTest {
    private final RouteSwitchContextResolver resolver = new RouteSwitchContextResolver(null);

    @Test
    void approvedExpertRouteRestoresDynamicRoleFromInteraction() {
        ChatInteractionRequest interaction = interaction("system-awareness");
        ChatInteractionClaimResult claim = new ChatInteractionClaimResult(
                interaction, Map.of("approved", true));

        RouteSwitchInput input = resolver.input(interaction, claim);
        var target = resolver.target(interaction, input);

        assertThat(target.type()).isEqualTo(RouteType.AGENT_RUNTIME);
        assertThat(target.runtimeProfile()).isEqualTo(RuntimeProfile.DOMAIN_EXPERT);
        assertThat(target.runtimeRoleName()).isEqualTo("system-awareness");
        assertThat(target.routeSource()).isEqualTo("user-confirmed");
    }

    @Test
    void expertRouteWithoutPersistedRoleFailsClosed() {
        ChatInteractionRequest interaction = interaction(null);
        ChatInteractionClaimResult claim = new ChatInteractionClaimResult(
                interaction, Map.of("approved", true));

        assertThatThrownBy(() -> resolver.input(interaction, claim))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("roleName");
    }

    private ChatInteractionRequest interaction(String runtimeRoleName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currentProvider", "domain-agent");
        payload.put("currentTargetId", "agent-a");
        payload.put("candidateProvider", "relay");
        payload.put("candidateTargetId", "relay");
        payload.put("candidateAccessName", "RE_system-awareness");
        payload.put("routeAction", "ROUTE_SINGLE");
        payload.put("originalQuery", "分析资产负债率");
        if (runtimeRoleName != null) {
            payload.put("candidateRuntimeRoleName", runtimeRoleName);
        }
        Instant now = Instant.parse("2026-08-05T10:00:00Z");
        return new ChatInteractionRequest(
                "interaction-1", "tenant-1", "user-1", "session-1", "run-a", null,
                "message-user", "message-assistant", "domain-agent", "binding-a", "session-1", null,
                ChatInteractionType.ROUTE_SWITCH_CONFIRMATION, ChatInteractionStatus.WAITING,
                payload, Map.of(), now.plusSeconds(3600), null, null, now, now);
    }
}
