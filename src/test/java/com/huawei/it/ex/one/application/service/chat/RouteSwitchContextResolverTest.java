package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.routing.RelayOutputMode;
import com.huawei.it.ex.one.domain.routing.RouteType;
import com.huawei.it.ex.one.domain.routing.RuntimeProfile;
import com.huawei.it.ex.one.domain.routing.SensitiveInformationAccessNameResolver;

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
        assertThat(target.relayOutputMode()).isEqualTo(RelayOutputMode.FULL_STREAM);
        assertThat(target.routeSource()).isEqualTo("user-confirmed");
    }

    @Test
    void approvedExpertRouteAcceptsMixedCaseRouteAction() {
        ChatInteractionRequest interaction = interaction(
                "RE_system-awareness", "system-awareness", "intent-expert", "领域专家",
                "RoUtE_SiNgLe");
        ChatInteractionClaimResult claim = new ChatInteractionClaimResult(
                interaction, Map.of("approved", true));

        RouteSwitchInput input = resolver.input(interaction, claim);
        var target = resolver.target(interaction, input);

        assertThat(target.runtimeProfile()).isEqualTo(RuntimeProfile.DOMAIN_EXPERT);
        assertThat(target.runtimeRoleName()).isEqualTo("system-awareness");
        assertThat(target.relayOutputMode()).isEqualTo(RelayOutputMode.FULL_STREAM);
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

    @Test
    void approvedSensitiveInformationRouteRestoresDelegateAndOriginalIntent() {
        RouteSwitchContextResolver sensitiveResolver = new RouteSwitchContextResolver(
                null, new SensitiveInformationAccessNameResolver("sensitive_information"));
        ChatInteractionRequest interaction = interaction(
                "sensitive_information", null, "intent-sensitive", "敏感信息");
        ChatInteractionClaimResult claim = new ChatInteractionClaimResult(
                interaction, Map.of("approved", true));

        RouteSwitchInput input = sensitiveResolver.input(interaction, claim);
        var target = sensitiveResolver.target(interaction, input);
        var restoredIntent = new AppliedRouteRecorder(null, null, null)
                .routeSwitchIntent(interaction, target);

        assertThat(target.type()).isEqualTo(RouteType.AGENT_RUNTIME);
        assertThat(target.runtimeProfile()).isEqualTo(RuntimeProfile.DELEGATE);
        assertThat(target.runtimeRoleName()).isNull();
        assertThat(target.relayOutputMode()).isEqualTo(RelayOutputMode.ANSWER_STREAM_ONLY);
        assertThat(restoredIntent.intentCode()).isEqualTo("intent-sensitive");
        assertThat(restoredIntent.intentName()).isEqualTo("敏感信息");
        assertThat(restoredIntent.candidateDomainAgentId()).isEqualTo("sensitive_information");
        assertThat(restoredIntent.slots()).containsEntry("routeAction", "ROUTE_SINGLE");
    }

    @Test
    void approvedSensitiveInformationRouteAcceptsLowerCaseRouteAction() {
        RouteSwitchContextResolver sensitiveResolver = new RouteSwitchContextResolver(
                null, new SensitiveInformationAccessNameResolver("sensitive_information"));
        ChatInteractionRequest interaction = interaction(
                "sensitive_information", null, "intent-sensitive", "敏感信息", "route_single");
        ChatInteractionClaimResult claim = new ChatInteractionClaimResult(
                interaction, Map.of("approved", true));

        RouteSwitchInput input = sensitiveResolver.input(interaction, claim);
        var target = sensitiveResolver.target(interaction, input);

        assertThat(target.runtimeProfile()).isEqualTo(RuntimeProfile.DELEGATE);
        assertThat(target.runtimeRoleName()).isNull();
        assertThat(target.relayOutputMode()).isEqualTo(RelayOutputMode.ANSWER_STREAM_ONLY);
    }

    private ChatInteractionRequest interaction(String runtimeRoleName) {
        return interaction("RE_system-awareness", runtimeRoleName, "intent-expert", "领域专家");
    }

    private ChatInteractionRequest interaction(String accessName,
                                               String runtimeRoleName,
                                               String intentCode,
                                               String intentName) {
        return interaction(accessName, runtimeRoleName, intentCode, intentName, "ROUTE_SINGLE");
    }

    private ChatInteractionRequest interaction(String accessName,
                                               String runtimeRoleName,
                                               String intentCode,
                                               String intentName,
                                               String routeAction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currentProvider", "domain-agent");
        payload.put("currentTargetId", "agent-a");
        payload.put("candidateProvider", "relay");
        payload.put("candidateTargetId", "relay");
        payload.put("candidateIntentCode", intentCode);
        payload.put("candidateIntentName", intentName);
        payload.put("candidateAccessName", accessName);
        payload.put("routeAction", routeAction);
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
