package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.service.runtime.DeferredDomainAgentBinding;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentBindingCommand;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.routing.RelayOutputMode;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RouteType;
import com.huawei.it.ex.one.domain.routing.RuntimeProfile;
import com.huawei.it.ex.one.domain.routing.SensitiveInformationAccessNameResolver;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

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
        assertThat(target.invocationSkillId()).isEqualTo("RE_system-awareness");
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
        assertThat(target.invocationSkillId()).isEqualTo("sensitive_information");
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
        assertThat(target.invocationSkillId()).isEqualTo("sensitive_information");
    }

    @Test
    void approvedNoMatchRouteUsesExplicitNoMatchInvocationSkill() {
        ChatInteractionRequest interaction = interaction(
                null, null, "intent-no-match", "未匹配", "NO_MATCH");
        ChatInteractionClaimResult claim = new ChatInteractionClaimResult(
                interaction, Map.of("approved", true));

        RouteTarget target = resolver.target(interaction, resolver.input(interaction, claim));

        assertThat(target.type()).isEqualTo(RouteType.AGENT_RUNTIME);
        assertThat(target.runtimeProfile()).isEqualTo(RuntimeProfile.DELEGATE);
        assertThat(target.invocationSkillId()).isEqualTo("NO_MATCH");
    }

    @Test
    void approvedDomainAgentSelectionUsesExecutionGuardedAtomicSwitch() {
        RuntimeBindingApplicationService bindingService = mock(RuntimeBindingApplicationService.class);
        RouteSwitchContextResolver bindingResolver = new RouteSwitchContextResolver(bindingService);
        ChatInteractionRequest interaction = domainAgentInteraction();
        RouteSwitchInput input = bindingResolver.input(
                interaction, new ChatInteractionClaimResult(interaction, Map.of("approved", true)));
        RunExecutionClaim claim = new RunExecutionClaim("run-b", "instance-1", 7L);
        RuntimeBinding candidate = binding("binding-b", "agent-b", RuntimeBindingStatus.ACTIVE, "run-b");
        when(bindingService.switchDomainAgentForInteraction(
                eq(interaction), any(DomainAgentBindingCommand.class), eq(claim)))
                .thenReturn(candidate);

        RouteSwitchBindingSelection selection = bindingResolver.selectBinding(
                interaction,
                input,
                new RouteSwitchBindingRequest(
                        new UserContext("tenant-1", "user-1", "account-1"),
                        new ChatSession("session-1", "tenant-1", "user-1", "title", "ACTIVE",
                                "web", Instant.now(), Instant.now()),
                        "run-b",
                        null,
                        claim));

        assertThat(selection.binding()).isEqualTo(candidate);
        assertThat(selection.sessionMode()).isEqualTo(
                com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode.RESUME);
        verify(bindingService).switchDomainAgentForInteraction(
                eq(interaction), any(DomainAgentBindingCommand.class), eq(claim));
        verify(bindingService).synchronizeDeferredDomainAgentActivation(candidate);
        verify(bindingService, never()).bindDomainAgentForRun(any(DomainAgentBindingCommand.class));
        verify(bindingService, never()).markNotRoutable(any(), any());
    }

    @Test
    void unsupportedAttachmentSelectionOnlyPreparesCandidateBinding() {
        RuntimeBindingApplicationService bindingService = mock(RuntimeBindingApplicationService.class);
        RouteSwitchContextResolver bindingResolver = new RouteSwitchContextResolver(bindingService);
        ChatInteractionRequest interaction = domainAgentInteraction();
        RouteSwitchInput input = bindingResolver.input(
                interaction, new ChatInteractionClaimResult(interaction, Map.of("approved", true)));
        RuntimeBinding candidate = binding("binding-b", "agent-b", RuntimeBindingStatus.ACTIVE, "run-b");
        DeferredDomainAgentBinding deferred = new DeferredDomainAgentBinding(candidate, null);
        when(bindingService.prepareDomainAgentForRun(any(DomainAgentBindingCommand.class)))
                .thenReturn(deferred);

        DeferredDomainAgentBinding selection =
                bindingResolver.prepareDomainAgentBindingForUnsupportedAttachment(
                        interaction,
                        input,
                        new RouteSwitchBindingRequest(
                                new UserContext("tenant-1", "user-1", "account-1"),
                                new ChatSession("session-1", "tenant-1", "user-1", "title", "ACTIVE",
                                "web", Instant.now(), Instant.now()),
                                "run-b",
                                null,
                                new RunExecutionClaim("run-b", "instance-1", 7L)));

        assertThat(selection).isSameAs(deferred);
        verify(bindingService).prepareDomainAgentForRun(any(DomainAgentBindingCommand.class));
        verify(bindingService, never()).bindDomainAgentForRun(any(DomainAgentBindingCommand.class));
    }

    @Test
    void candidatePreparationFailureDoesNotMutateSourceBinding() {
        RuntimeBindingApplicationService bindingService = mock(RuntimeBindingApplicationService.class);
        RouteSwitchContextResolver bindingResolver = new RouteSwitchContextResolver(bindingService);
        ChatInteractionRequest interaction = domainAgentInteraction();
        RouteSwitchInput input = bindingResolver.input(
                interaction, new ChatInteractionClaimResult(interaction, Map.of("approved", true)));
        when(bindingService.prepareDomainAgentForRun(any(DomainAgentBindingCommand.class)))
                .thenThrow(new IllegalStateException("candidate create failed"));

        assertThatThrownBy(() -> bindingResolver.prepareDomainAgentBindingForUnsupportedAttachment(
                interaction,
                input,
                new RouteSwitchBindingRequest(
                        new UserContext("tenant-1", "user-1", "account-1"),
                        new ChatSession("session-1", "tenant-1", "user-1", "title", "ACTIVE",
                                "web", Instant.now(), Instant.now()),
                        "run-b",
                        null,
                        new RunExecutionClaim("run-b", "instance-1", 7L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("candidate create failed");

        verify(bindingService, never()).bindDomainAgentForRun(any(DomainAgentBindingCommand.class));
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

    private ChatInteractionRequest domainAgentInteraction() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currentProvider", "domain-agent");
        payload.put("currentTargetId", "agent-a");
        payload.put("candidateProvider", "domain-agent");
        payload.put("candidateTargetId", "agent-b");
        payload.put("candidateIntentCode", "intent-b");
        payload.put("candidateIntentName", "技能B");
        payload.put("originalQuery", "分析附件");
        payload.put("refusalCode", "REFUSED");
        Instant now = Instant.parse("2026-08-30T10:00:00Z");
        return new ChatInteractionRequest(
                "interaction-domain", "tenant-1", "user-1", "session-1", "run-a", "run-b",
                "message-user", "message-assistant", "domain-agent", "binding-a", "session-1", null,
                ChatInteractionType.ROUTE_SWITCH_CONFIRMATION, ChatInteractionStatus.RESPONDING,
                payload, Map.of(), now.plusSeconds(3600), null, null, now, now);
    }

    private RuntimeBinding binding(
            String id,
            String skillId,
            RuntimeBindingStatus status,
            String runId) {
        Instant now = Instant.parse("2026-08-30T10:00:00Z");
        return new RuntimeBinding(
                id,
                "tenant-1",
                "user-1",
                "session-1",
                RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER,
                "message-assistant",
                "session-1",
                status,
                runId,
                null,
                now,
                now,
                Map.of("domainAgentId", skillId));
    }
}
