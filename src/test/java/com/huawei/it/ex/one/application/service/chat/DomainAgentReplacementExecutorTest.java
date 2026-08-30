/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceGate;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.DeferredDomainAgentBinding;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentBindingCommand;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

class DomainAgentReplacementExecutorTest {

    @Test
    void unsupportedAttachmentRetainsReplacementBindingWithoutInvokingRuntime() {
        AgentRuntimeExecutor runtimeExecutor = mock(AgentRuntimeExecutor.class);
        RuntimeBindingApplicationService bindingService = mock(RuntimeBindingApplicationService.class);
        AppliedRouteRecorder routeRecorder = mock(AppliedRouteRecorder.class);
        RouteResolutionCoordinator routeResolution = mock(RouteResolutionCoordinator.class);
        DomainAgentProperties properties = new DomainAgentProperties();
        DomainAgentBindingPolicy bindingPolicy = new DomainAgentBindingPolicy(bindingService, properties);
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        RuntimeBindingDispatchCompensator bindingCompensator = mock(RuntimeBindingDispatchCompensator.class);
        AgentDataPersistenceGate persistenceGate = mock(AgentDataPersistenceGate.class);
        DomainAgentReplacementExecutor executor = new DomainAgentReplacementExecutor(
                runtimeExecutor,
                bindingService,
                routeRecorder,
                routeResolution,
                bindingPolicy,
                new DomainAgentRefusalEventFactory(),
                leaseService,
                Schedulers.immediate(),
                Schedulers.immediate(),
                properties,
                bindingCompensator,
                persistenceGate);

        Instant now = Instant.parse("2026-08-30T00:00:00Z");
        RuntimeBinding bindingA = binding("binding-a", "skill-a", RuntimeBindingStatus.ACTIVE, now);
        RuntimeBinding rejectedA = bindingA.withStatus(RuntimeBindingStatus.CANCELLED);
        RuntimeBinding bindingB = binding("binding-b", "skill-b", RuntimeBindingStatus.ACTIVE, now);
        DeferredDomainAgentBinding deferred = new DeferredDomainAgentBinding(bindingB, null);
        when(bindingService.prepareDomainAgentForRun(any(DomainAgentBindingCommand.class)))
                .thenReturn(deferred);
        when(leaseService.isCurrentOwnerRunning(any())).thenReturn(true);

        AgentDataPersistenceState persistenceState = AgentDataPersistenceState.full();
        Map<String, Object> payload = Map.of(
                "source", "chatservice",
                "sourceType", "domain-agent-attachment-validation",
                "code", "DOMAIN_AGENT_ATTACHMENT_TYPE_UNSUPPORTED",
                "skillId", "skill-b",
                "skillName", "技能B",
                "supportedAttachmentTypes", List.of(".xlsx"),
                "unsupportedAttachmentTypes", List.of(".pdf"),
                "unsupportedAttachments", List.of(Map.of(
                        "documentId", "doc-1", "name", "report.pdf", "extension", ".pdf")));
        when(persistenceGate.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(AgentDataPersistenceGate.Decision.unsupported(
                        persistenceState, payload)));

        RouteTarget routeA = RouteTarget.domainAgent("skill-a", "intent-agent", 0.9, "first");
        RouteTarget routeB = RouteTarget.domainAgent("skill-b", "intent-agent", 0.8, "replacement");
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-1", 1L);
        AtomicReference<RouteTarget> routeRef = new AtomicReference<>(routeA);
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>(rejectedA);
        MessageSkillTracker messageSkill = new MessageSkillTracker();
        messageSkill.replace("skill-a");
        AtomicReference<DeferredDomainAgentBinding> deferredBindingRef = new AtomicReference<>();
        ChatSession session = new ChatSession(
                "session-1", "tenant-1", "user-1", "title", "ACTIVE", "web", now, now);
        ChatCommand command = new ChatCommand(
                "command-1", null, null, "session-1", null, "web", "query",
                List.of(), Map.of(), null, null, ChatRunMode.NEXT, null, null, null);
        DomainAgentRunContext context = new DomainAgentRunContext(
                command,
                "run-1",
                "message-1",
                session,
                MemoryContext.empty(),
                routeA,
                new UserContext("tenant-1", "user-1", "account-1"),
                routeRef,
                bindingRef,
                claim,
                RuntimeForwardHeaders.empty(),
                TraceContext.empty(),
                null,
                List.of(),
                new HashSet<>(List.of("skill-a")),
                0,
                "query",
                persistenceState,
                messageSkill,
                new AtomicReference<>(),
                deferredBindingRef);
        DomainAgentRefusal refusal = new DomainAgentRefusal(
                "REFUSED", "OUT_OF_SCOPE", true, "not supported", "skill-a");
        DomainAgentRerouteContext reroute = new DomainAgentRerouteContext(
                context,
                refusal,
                new DomainAgentRejectReason("技能A", "not supported"),
                "skill-a",
                new HashSet<>(List.of("skill-a")),
                "query",
                null);
        @SuppressWarnings("unchecked")
        Function<DomainAgentRunContext, Flux<ChatEvent>> continuation = mock(Function.class);

        List<ChatEvent> events = executor.continueWithDomainAgent(
                        reroute, RouteSignalResult.of(routeB), routeB, continuation)
                .collectList()
                .block();

        assertThat(events).extracting(ChatEvent::type)
                .containsExactly("runtime.progress", "runtime.card", "message.completed");
        assertThat(routeRef).hasValue(routeB);
        assertThat(bindingRef).hasValue(bindingB);
        assertThat(bindingRef.get().status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
        assertThat(persistenceState.runtimeDispatchStarted()).isFalse();
        assertThat(messageSkill.current()).isEqualTo("skill-b");
        assertThat(deferredBindingRef).hasValue(deferred);
        verify(bindingService).prepareDomainAgentForRun(any(DomainAgentBindingCommand.class));
        verify(bindingService, never()).bindDomainAgentForRun(any(DomainAgentBindingCommand.class));
        verify(bindingService, never()).markNotRoutable(any(), any());
        verify(bindingService, never()).cancelActiveForRun(bindingB, "run-1");
        verify(routeRecorder).bindResolvedRouteRequired(
                "run-1", routeB, bindingB, claim, persistenceState);
        ArgumentCaptor<PendingRouteMemoryDecision> pendingDecision =
                ArgumentCaptor.forClass(PendingRouteMemoryDecision.class);
        verify(routeRecorder).deferRouteMemoryDecision(
                any(AtomicReference.class), pendingDecision.capture());
        assertThat(pendingDecision.getValue().query()).isEqualTo("query");
        assertThat(pendingDecision.getValue().route()).isEqualTo(routeB);
        verify(routeRecorder, never()).recordAppliedRouteDecision(any());
        verifyNoInteractions(runtimeExecutor, continuation);
    }

    private RuntimeBinding binding(
            String bindingId,
            String skillId,
            RuntimeBindingStatus status,
            Instant now) {
        return new RuntimeBinding(
                bindingId,
                "tenant-1",
                "user-1",
                "session-1",
                RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER,
                "leaf-1",
                "session-1",
                status,
                "run-1",
                null,
                now,
                now,
                Map.of("domainAgentId", skillId, "routeSource", "intent-agent"));
    }
}
