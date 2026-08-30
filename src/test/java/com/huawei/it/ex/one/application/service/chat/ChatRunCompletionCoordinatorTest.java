/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.service.runtime.DeferredDomainAgentBinding;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class ChatRunCompletionCoordinatorTest {

    @Test
    void completedCommitSynchronizesBindingBeforePublishingDeferredRouteSwitchEvent() {
        ChatRunTerminalCommitService terminalService = mock(ChatRunTerminalCommitService.class);
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        RuntimeBindingApplicationService bindingService = mock(RuntimeBindingApplicationService.class);
        AppliedRouteRecorder routeRecorder = mock(AppliedRouteRecorder.class);
        ChatRunCompletionCoordinator coordinator = new ChatRunCompletionCoordinator(
                null, null, null, terminalService, streamService, bindingService, null, routeRecorder);
        RuntimeBinding binding = binding();
        ChatEvent applied = RuntimeEvent.metadata("run-1", "session-1", Map.of(
                "source", "chatservice",
                "sourceType", "route-switch-applied"));
        ChatEvent completed = RunCompletedEvent.of("run-1", "session-1");
        AtomicReference<PendingRouteSwitchAppliedEvent> pendingAppliedRef = new AtomicReference<>(
                new PendingRouteSwitchAppliedEvent(applied, binding.id()));
        AtomicReference<DeferredDomainAgentBinding> deferredRef = new AtomicReference<>(
                new DeferredDomainAgentBinding(binding, null));
        PendingRouteMemoryDecision pendingDecision = pendingDecision();
        RunEventPipelineContext context = context(
                binding,
                new AtomicReference<>(pendingDecision),
                deferredRef,
                pendingAppliedRef);
        when(terminalService.commitCompleted(any())).thenReturn(
                new ChatRunTerminalCommitService.CommitResult(
                        completed, binding, true, List.of(applied)));

        ChatEvent result = coordinator.commitCompleted(
                new ChatRunCompletionCoordinator.CompletionPlan(
                        completed,
                        new ChatRunCompletionCoordinator.CompletionMessageTarget(
                                true, true, "msg-assistant"),
                        null),
                context);

        assertThat(result).isSameAs(completed);
        assertThat(pendingAppliedRef).hasNullValue();
        assertThat(deferredRef).hasNullValue();
        InOrder order = inOrder(bindingService, routeRecorder, streamService);
        order.verify(bindingService).synchronizeDeferredDomainAgentActivation(binding);
        order.verify(routeRecorder).recordCommittedRouteDecision(pendingDecision, binding);
        order.verify(streamService).publishPersisted(applied);
        order.verify(streamService).publishPersisted(completed);
    }

    @Test
    void failedCommitDiscardsPendingRouteSwitchAppliedEvent() {
        ChatRunTerminalCommitService terminalService = mock(ChatRunTerminalCommitService.class);
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        RuntimeBindingApplicationService bindingService = mock(RuntimeBindingApplicationService.class);
        ChatRunCompletionCoordinator coordinator = new ChatRunCompletionCoordinator(
                null, null, null, terminalService, streamService, bindingService, null, null);
        RuntimeBinding binding = binding();
        ChatEvent applied = RuntimeEvent.metadata("run-1", "session-1", Map.of(
                "source", "chatservice",
                "sourceType", "route-switch-applied"));
        ChatEvent failed = event("run.failed");
        AtomicReference<PendingRouteSwitchAppliedEvent> pendingAppliedRef = new AtomicReference<>(
                new PendingRouteSwitchAppliedEvent(applied, binding.id()));
        RunEventPipelineContext context = context(
                binding,
                new AtomicReference<>(),
                new AtomicReference<>(),
                pendingAppliedRef);
        when(terminalService.commitTerminalOnly(any())).thenReturn(
                new ChatRunTerminalCommitService.CommitResult(failed, binding));

        coordinator.commitTerminalOnly(failed, context);

        assertThat(pendingAppliedRef).hasNullValue();
        verify(streamService, never()).publishPersisted(applied);
        verify(streamService).publishPersisted(failed);
    }

    @Test
    void completedCommitRecordsAndConsumesPendingRouteMemoryDecisionOnce() {
        AppliedRouteRecorder routeRecorder = mock(AppliedRouteRecorder.class);
        ChatRunCompletionCoordinator coordinator = coordinator(routeRecorder);
        RuntimeBinding binding = binding();
        PendingRouteMemoryDecision pending = pendingDecision();
        AtomicReference<PendingRouteMemoryDecision> pendingRef = new AtomicReference<>(pending);
        RunEventPipelineContext context = context(binding, pendingRef);
        ChatEvent completed = event("run.completed");

        coordinator.recordRouteMemoryAfterCommitted(completed, context);
        coordinator.recordRouteMemoryAfterCommitted(completed, context);

        verify(routeRecorder).recordCommittedRouteDecision(pending, binding);
        assertThat(pendingRef).hasNullValue();
    }

    @Test
    void failedCommitClearsPendingRouteMemoryDecisionWithoutRecordingIt() {
        AppliedRouteRecorder routeRecorder = mock(AppliedRouteRecorder.class);
        ChatRunCompletionCoordinator coordinator = coordinator(routeRecorder);
        AtomicReference<PendingRouteMemoryDecision> pendingRef =
                new AtomicReference<>(pendingDecision());

        coordinator.recordRouteMemoryAfterCommitted(
                event("run.failed"), context(binding(), pendingRef));

        verify(routeRecorder, never()).recordCommittedRouteDecision(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(pendingRef).hasNullValue();
    }

    private ChatRunCompletionCoordinator coordinator(AppliedRouteRecorder routeRecorder) {
        return new ChatRunCompletionCoordinator(
                null, null, null, null, null, null, null, routeRecorder);
    }

    private RunEventPipelineContext context(
            RuntimeBinding binding,
            AtomicReference<PendingRouteMemoryDecision> pendingRef) {
        return context(binding, pendingRef, new AtomicReference<>(), new AtomicReference<>());
    }

    private RunEventPipelineContext context(
            RuntimeBinding binding,
            AtomicReference<PendingRouteMemoryDecision> pendingRef,
            AtomicReference<DeferredDomainAgentBinding> deferredRef,
            AtomicReference<PendingRouteSwitchAppliedEvent> pendingAppliedRef) {
        Instant now = Instant.parse("2026-08-30T00:00:00Z");
        ChatSession session = new ChatSession(
                "session-1", "tenant-1", "user-1", "title", "ACTIVE", "web", now, now);
        return new RunEventPipelineContext(
                new UserContext("tenant-1", "user-1", "account-1"),
                session,
                null,
                new AtomicReference<>(RouteTarget.domainAgent("skill-b", "intent-agent", 1.0, "selected")),
                new AtomicReference<>(binding),
                new AssistantAssembly(),
                "run-1",
                new RunExecutionClaim("run-1", "instance-1", 1L),
                new AtomicReference<>(),
                null,
                null,
                List.of(),
                deferredRef,
                pendingRef,
                pendingAppliedRef);
    }

    private PendingRouteMemoryDecision pendingDecision() {
        return new PendingRouteMemoryDecision(
                new UserContext("tenant-1", "user-1", "account-1"),
                "session-1",
                "run-1",
                "query",
                null,
                RouteTarget.domainAgent("skill-b", "intent-agent", 1.0, "selected"));
    }

    private RuntimeBinding binding() {
        Instant now = Instant.parse("2026-08-30T00:00:00Z");
        return new RuntimeBinding(
                "binding-b", "tenant-1", "user-1", "session-1", "domain-agent",
                "leaf-b", "session-1", RuntimeBindingStatus.ACTIVE, "run-1", null,
                now, now, Map.of("domainAgentId", "skill-b"));
    }

    private ChatEvent event(String type) {
        ChatEvent event = mock(ChatEvent.class);
        when(event.type()).thenReturn(type);
        return event;
    }
}
