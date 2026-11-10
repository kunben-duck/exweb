/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.service.runtime.DeferredDomainAgentBinding;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/** Executes the existing intent-clarification continuation workflow. */
final class IntentClarificationRunCoordinator {
    private final IntentClarificationContextAssembler clarificationAssembler;
    private final InteractionEventFactory interactionEventFactory;
    private final InteractionRunLifecycle lifecycle;
    private final ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator;
    private final ChatEventPersistenceCoordinator eventPersistenceCoordinator;
    private final ChatRunAdmissionCoordinator admissionCoordinator;
    private final RunMemoryContextAssembler memoryAssembler;

    IntentClarificationRunCoordinator(
            IntentClarificationContextAssembler clarificationAssembler,
            InteractionEventFactory interactionEventFactory,
            InteractionRunLifecycle lifecycle,
            ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            ChatRunAdmissionCoordinator admissionCoordinator,
            RunMemoryContextAssembler memoryAssembler) {
        this.clarificationAssembler = clarificationAssembler;
        this.interactionEventFactory = interactionEventFactory;
        this.lifecycle = lifecycle;
        this.runtimeDispatchCoordinator = runtimeDispatchCoordinator;
        this.eventPersistenceCoordinator = eventPersistenceCoordinator;
        this.admissionCoordinator = admissionCoordinator;
        this.memoryAssembler = memoryAssembler;
    }

    IntentClarificationRunCoordinator(
            IntentClarificationContextAssembler clarificationAssembler,
            InteractionEventFactory interactionEventFactory,
            InteractionRunLifecycle lifecycle,
            ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            ChatRunAdmissionCoordinator admissionCoordinator) {
        this(clarificationAssembler, interactionEventFactory, lifecycle, runtimeDispatchCoordinator,
                eventPersistenceCoordinator, admissionCoordinator, null);
    }

    Flux<ChatEvent> execute(Request request) {
        ChatInteractionRequest interaction = request.claim().request();
        ChatCommand command = clarificationAssembler.command(
                request.user(),
                request.session(),
                interaction,
                request.claim().responsePayload(),
                request.input());
        RuntimeEvent responseEvent = interactionEventFactory.clarificationResponseEvent(
                request.runId(),
                request.session().id(),
                interaction,
                request.claim().responsePayload());
        MemoryContext memory = memoryAssembler == null
                ? MemoryContext.empty()
                : memoryAssembler.assemble(
                        command, request.session().currentLeafMessageId(), interaction.userMessageId());
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>();
        AtomicReference<RouteTarget> routeRef = new AtomicReference<>();
        AtomicReference<RuntimeSessionMode> runtimeSessionModeRef =
                new AtomicReference<>(RuntimeSessionMode.RESUME);
        // 普通澄清可新增 user 回答，AMBIGUOUS_ROUTE 的 OTHER 复用原问答；由消息策略统一决定。
        ChatRunAdmissionCommitService.AdmissionResult admission = admitClarification(request, interaction);
        ChatRunMessagePlan messagePlan = admission.messagePlan();
        ChatRun run = admission.run();
        ChatCommand routedCommand = command.withIntentExpertScope(admission.intentExpertScope());
        lifecycle.trackRun(
                request.startAttempt(),
                run,
                "after-intent-interaction-run-create");
        lifecycle.synchronizeCommittedRunCache(run);
        RunExecutionClaim executionClaim;
        try {
            executionClaim = lifecycle.startExecution(run, interaction);
        } catch (RuntimeException ex) {
            return lifecycle.failInitialization(run, interaction, ex);
        }
        lifecycle.trackExecution(
                request.startAttempt(),
                executionClaim,
                "after-intent-interaction-execution-create");
        return executeStarted(request, new StartedClarification(interaction, routedCommand, responseEvent, memory,
                bindingRef, routeRef, runtimeSessionModeRef, messagePlan, run, executionClaim));
    }

    private ChatRunAdmissionCommitService.AdmissionResult admitClarification(
            Request request, ChatInteractionRequest interaction) {
        return admissionCoordinator.admitIntentClarification(
                new ChatRunAdmissionCoordinator.IntentClarificationAdmission(
                        request.user(),
                        request.session(),
                        request.runId(),
                        interaction,
                        request.input().messageText(),
                        request.input().currentAttachments(),
                        lifecycle.metadata(interaction),
                        request.input().runtimeMetadata(),
                        request.startAttempt() == null ? null : request.startAttempt().channel()));
    }

    private record StartedClarification(ChatInteractionRequest interaction, ChatCommand routedCommand,
                                        RuntimeEvent responseEvent, MemoryContext memory,
                                        AtomicReference<RuntimeBinding> bindingRef, AtomicReference<RouteTarget> routeRef,
                                        AtomicReference<RuntimeSessionMode> runtimeSessionModeRef,
                                        ChatRunMessagePlan messagePlan, ChatRun run, RunExecutionClaim executionClaim) { }

    private Flux<ChatEvent> executeStarted(Request request, StartedClarification started) {
        AssistantAssembly assistant = new AssistantAssembly(
                RouteSwitchRerouteContext.continuationPersistenceState(started.routedCommand()));
        // 路由与终态管线共享待提交引用，附件拒绝不能只在局部事件源中确认 Binding 成功。
        AtomicReference<DeferredDomainAgentBinding> deferredDomainAgentBindingRef = new AtomicReference<>();
        AtomicReference<PendingRouteMemoryDecision> pendingRouteMemoryDecisionRef = new AtomicReference<>();
        AtomicReference<PendingRouteSwitchAppliedEvent> pendingSwitch = new AtomicReference<>();
        AtomicReference<java.util.Map<String, Object>> pendingInteractionPayloadRef = new AtomicReference<>();
        RunEventPipelineContext context = new RunEventPipelineContext(
                request.user(),
                request.session(),
                started.messagePlan(),
                started.routeRef(),
                started.bindingRef(),
                assistant,
                request.runId(),
                started.executionClaim(),
                pendingInteractionPayloadRef,
                started.interaction(),
                request.startAttempt(),
                request.input().cumulativeDocumentIds(),
                deferredDomainAgentBindingRef,
                pendingRouteMemoryDecisionRef, pendingSwitch);
        // 折叠问题用于 RouteMemory；实际 Intent 输入另行传递，避免把澄清回答丢出上下文。
        String foldedRouteQuery = clarificationAssembler.routeMemoryQuery(
                started.messagePlan(), started.interaction(), request.input().intentQuery());
        try {
            return eventPersistenceCoordinator.executeAfterRunStarted(
                    context,
                    () -> RouteSwitchRerouteContext.afterResponse(started.routedCommand(), started.responseEvent(),
                            () -> runtimeDispatchCoordinator.execute(routeRequest(request, started, context, foldedRouteQuery))));
        } catch (RuntimeException ex) {
            return lifecycle.failContinuation(context, ex);
        }
    }

    private RoutePipelineRequest routeRequest(Request request, StartedClarification started,
                                               RunEventPipelineContext context, String foldedRouteQuery) {
        return new RoutePipelineRequest(
                request.user(),
                request.session(),
                started.routedCommand(),
                request.input().cumulativeAttachments(),
                request.input().cumulativeDocuments(),
                started.memory(),
                request.runId(),
                started.messagePlan().parentMessageId(),
                request.forwardHeaders(),
                request.traceContext(),
                started.routeRef(),
                started.bindingRef(),
                started.runtimeSessionModeRef(),
                started.executionClaim(),
                started.run(),
                foldedRouteQuery,
                request.input().intentQuery(),
                foldedRouteQuery,
                request.input().runtimeMetadata(),
                request.input().agentMode(),
                new RuntimeBindingDispatchLifecycle(),
                context.assistant().persistenceState(),
                context.assistant().messageSkill(),
                context.pendingInteractionPayloadRef(),
                context.deferredDomainAgentBindingRef(),
                context.pendingRouteMemoryDecisionRef(), context.pendingRouteSwitchAppliedEventRef());
    }

    record Request(
            UserContext user,
            ChatInteractionClaimResult claim,
            String runId,
            ChatSession session,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            RunStartAttempt startAttempt,
            IntentClarificationContextAssembler.ContinuationInput input
    ) {
    }
}
