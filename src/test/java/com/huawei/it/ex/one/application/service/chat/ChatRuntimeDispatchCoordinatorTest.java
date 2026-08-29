package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceGate;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class ChatRuntimeDispatchCoordinatorTest {

    @Test
    void unsupportedAttachmentCompletesWithoutPersistingRouteOrSubscribingRuntime() {
        RouteSignalApplicationService routeSignals = mock(RouteSignalApplicationService.class);
        ChatEventPersistenceCoordinator eventPersistence = mock(ChatEventPersistenceCoordinator.class);
        InteractionEventFactory interactionEvents = mock(InteractionEventFactory.class);
        AppliedRouteRecorder routeRecorder = mock(AppliedRouteRecorder.class);
        RouteResolutionCoordinator routeResolution = mock(RouteResolutionCoordinator.class);
        DomainAgentRefusalCoordinator refusalCoordinator = mock(DomainAgentRefusalCoordinator.class);
        SystemResponseExecutor systemResponses = mock(SystemResponseExecutor.class);
        AgentRuntimeExecutor runtimeExecutor = mock(AgentRuntimeExecutor.class);
        RuntimeBindingDispatchCompensator bindingCompensator = mock(RuntimeBindingDispatchCompensator.class);
        AgentDataPersistenceGate persistenceGate = mock(AgentDataPersistenceGate.class);
        ChatRuntimeDispatchCoordinator coordinator = new ChatRuntimeDispatchCoordinator(
                routeSignals,
                eventPersistence,
                interactionEvents,
                routeRecorder,
                routeResolution,
                refusalCoordinator,
                systemResponses,
                runtimeExecutor,
                bindingCompensator,
                persistenceGate);

        RouteTarget route = RouteTarget.domainAgent("skill-1", "intent", 0.9, "matched");
        RuntimeBinding binding = mock(RuntimeBinding.class);
        RouteSignalResult signal = RouteSignalResult.of(route);
        when(routeResolution.resolve(any(), any())).thenReturn(
                new RouteResolutionCoordinator.RouteExecutionResolution(
                        route, binding, RuntimeSessionMode.NEW, null, null, null, false, Map.of()));
        when(eventPersistence.requireCurrentOwnerRunning(any(), anyString())).thenReturn(Mono.empty());
        when(bindingCompensator.cleanup(any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(Mono.empty());
        Map<String, Object> payload = Map.of(
                "source", "chatservice",
                "sourceType", "domain-agent-attachment-validation",
                "code", "DOMAIN_AGENT_ATTACHMENT_TYPE_UNSUPPORTED",
                "skillId", "skill-1",
                "skillName", "技能一",
                "supportedAttachmentTypes", List.of(".xlsx"),
                "unsupportedAttachmentTypes", List.of(".pdf"),
                "unsupportedAttachments", List.of(Map.of(
                        "documentId", "doc-1", "name", "report.pdf", "extension", ".pdf")));
        when(persistenceGate.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(AgentDataPersistenceGate.Decision.unsupported(
                        AgentDataPersistenceState.full(), payload)));

        ChatSession session = mock(ChatSession.class);
        when(session.id()).thenReturn("session-1");
        RuntimeBindingDispatchLifecycle lifecycle = new RuntimeBindingDispatchLifecycle();
        AtomicReference<RouteTarget> routeRef = new AtomicReference<>();
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>();
        RoutePipelineRequest request = new RoutePipelineRequest(
                new UserContext("tenant-1", "user-1", "account-1"),
                session,
                new ChatCommand("command-1", null, null, "session-1", null, "web", "query",
                        List.of(), Map.of(), null, null, ChatRunMode.NEXT, null, null, null),
                List.of(),
                List.of(),
                MemoryContext.empty(),
                "run-1",
                null,
                RuntimeForwardHeaders.empty(),
                TraceContext.empty(),
                routeRef,
                bindingRef,
                new AtomicReference<>(RuntimeSessionMode.RESUME),
                new RunExecutionClaim("run-1", "instance-1", 1L),
                mock(ChatRun.class),
                "query",
                "query",
                "query",
                null,
                null,
                lifecycle,
                AgentDataPersistenceState.full());

        List<ChatEvent> events = coordinator.executeResolved(request, signal).collectList().block();

        assertThat(events).extracting(ChatEvent::type)
                .containsExactly("runtime.progress", "runtime.card", "message.completed");
        assertThat(routeRef).hasValue(route);
        assertThat(bindingRef).hasValue(binding);
        verify(bindingCompensator, atLeastOnce()).cleanup(
                any(), anyString(), anyString(), any(), anyString());
        verifyNoInteractions(runtimeExecutor, routeRecorder);
    }
}
