/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.IntentExpertSelectionPayload;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.service.runtime.DeferredDomainAgentBinding;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.IntentExpertScope;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Preserves post-admission standard run route and Runtime execution order. */
final class StandardRunRuntimeCoordinator {
    private final IntentClarificationContextAssembler clarificationAssembler;
    private final RouteResolutionCoordinator routeResolutionCoordinator;
    private final ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator;
    private final ChatEventPersistenceCoordinator eventPersistenceCoordinator;
    private final ChatRunFailureCoordinator failureCoordinator;
    private final LocalChatRunExecutionRegistry runExecutionRegistry;

    StandardRunRuntimeCoordinator(
            IntentClarificationContextAssembler clarificationAssembler,
            RouteResolutionCoordinator routeResolutionCoordinator,
            ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            ChatRunFailureCoordinator failureCoordinator,
            LocalChatRunExecutionRegistry runExecutionRegistry) {
        this.clarificationAssembler = clarificationAssembler;
        this.routeResolutionCoordinator = routeResolutionCoordinator;
        this.runtimeDispatchCoordinator = runtimeDispatchCoordinator;
        this.eventPersistenceCoordinator = eventPersistenceCoordinator;
        this.failureCoordinator = failureCoordinator;
        this.runExecutionRegistry = runExecutionRegistry;
    }

    RuntimePlan prepare(
            StandardRunInputPreparer.PreparedRun prepared,
            StandardRunAdmissionCoordinator.Admission admission) {
        ChatCommand runCommand = commandForExecution(
                prepared.command(), admission.messagePlan(), admission.intentExpertScope());
        String currentRouteQuery = clarificationAssembler.routeMemoryQuery(
                admission.messagePlan(), null);
        String intentQuery = IntentClarificationContextAssembler.answerWithAttachments(
                runCommand.message(), prepared.attachments());
        String runtimeBindingLeafId = runtimeBindingLeafId(admission.messagePlan());
        RuntimeBindingDispatchLifecycle bindingLifecycle = new RuntimeBindingDispatchLifecycle();
        bindingLifecycle.trackAdmissionCancellations(admission.restorableAdmissionCancellations());
        return new RuntimePlan(
                prepared,
                admission,
                runCommand,
                currentRouteQuery,
                intentQuery,
                runtimeBindingLeafId,
                new AtomicReference<>(),
                new AtomicReference<>(),
                new AtomicReference<>(RuntimeSessionMode.RESUME),
                new AtomicReference<>(),
                new AssistantAssembly(),
                bindingLifecycle,
                new AtomicReference<>(),
                new AtomicReference<>());
    }

    Flux<ChatEvent> execute(RuntimePlan plan, RunExecutionClaim executionClaim) {
        return execute(plan, executionClaim, CandidateSwitchRouteTrace.empty());
    }

    Flux<ChatEvent> executeCandidateSwitch(
            RuntimePlan plan,
            RunExecutionClaim executionClaim,
            CandidateSwitchRouteTrace routeTrace) {
        return execute(plan, executionClaim, routeTrace == null
                ? CandidateSwitchRouteTrace.empty()
                : routeTrace);
    }

    private Flux<ChatEvent> execute(
            RuntimePlan plan,
            RunExecutionClaim executionClaim,
            CandidateSwitchRouteTrace routeTrace) {
        StandardRunInputPreparer.PreparedRun prepared = plan.prepared();
        try {
            RunEventPipelineContext context = pipelineContext(plan, executionClaim);
            return eventPersistenceCoordinator.executeAfterRunStarted(context, () -> {
                RoutePipelineRequest request = new RoutePipelineRequest(
                        prepared.user(),
                        prepared.session(),
                        plan.runCommand(),
                        prepared.attachments(),
                        prepared.documents(),
                        prepared.memory(),
                        prepared.runId(),
                        plan.runtimeBindingLeafId(),
                        prepared.forwardHeaders(),
                        prepared.traceContext(),
                        plan.routeRef(),
                        plan.bindingRef(),
                        plan.runtimeSessionModeRef(),
                        executionClaim,
                        plan.admission().run(),
                        plan.currentRouteQuery(),
                        plan.intentQuery(),
                        plan.intentQuery(),
                        null,
                        prepared.command().agentMode(),
                        plan.bindingLifecycle(),
                        plan.assistant().persistenceState(),
                        plan.assistant().messageSkill(),
                        plan.pendingInteractionPayloadRef(),
                        plan.deferredDomainAgentBindingRef(),
                        plan.pendingRouteMemoryDecisionRef());
                Flux<ChatEvent> runtimeEvents = Flux.defer(() -> runtimeDispatchCoordinator.execute(request, () -> routeResolutionCoordinator.prepareInitial(
                        new RouteResolutionCoordinator.InitialRoutePreparation(
                                prepared.user(),
                                prepared.session(),
                                prepared.runId(),
                                plan.runtimeBindingLeafId(),
                                plan.runCommand(),
                                prepared.explicitRuntimeTarget(),
                                prepared.forceReroute(),
                                plan.routeRef(),
                                plan.bindingRef(),
                                plan.runtimeSessionModeRef(),
                                prepared.command().agentMode(),
                                executionClaim,
                                plan.bindingLifecycle(),
                                !prepared.documents().isEmpty(),
                                plan.deferredDomainAgentBindingRef()))));
                Flux<ChatEvent> selectedAndRuntimeEvents = runtimeEvents;
                if (plan.admission().emitIntentExpertSelection()
                        && plan.runCommand().intentExpertScope() != null) {
                    selectedAndRuntimeEvents = Flux.concat(
                            Flux.just(RuntimeEvent.metadata(
                                    prepared.runId(),
                                    prepared.session().id(),
                                    IntentExpertSelectionPayload.create(
                                            plan.runCommand().intentExpertScope()))),
                            runtimeEvents);
                }
                return replayBeforeRuntime(
                        routeTrace.eventsFor(prepared.runId(), prepared.session().id()),
                        selectedAndRuntimeEvents);
            });
        } catch (RuntimeException ex) {
            RunEventPipelineContext context = pipelineContext(plan, executionClaim);
            return eventPersistenceCoordinator.persistAndPublish(
                            Flux.just(failureCoordinator.runtimeErrorEvent(
                                    prepared.runId(), prepared.session().id(), ex)),
                            context)
                    .doFinally(ignored -> runExecutionRegistry.complete(executionClaim));
        }
    }

    private Flux<ChatEvent> replayBeforeRuntime(List<ChatEvent> replay, Flux<ChatEvent> runtime) {
        if (replay.isEmpty()) {
            return runtime;
        }
        return Flux.defer(() -> {
            ChatEvent marker = replay.getLast();
            if (!"runtime.progress".equals(marker.type())
                    || !"chatservice".equals(marker.payload().get("source"))
                    || !"candidate-skill-switch".equals(marker.payload().get("sourceType"))) {
                return Flux.error(new IllegalStateException("Candidate switch replay must end with its switch marker"));
            }
            Sinks.One<Void> persisted = Sinks.one();
            // Emission can be prefetched; only the final marker's commit acknowledges the whole prefix.
            return Flux.concat(
                    Flux.fromIterable(replay.subList(0, replay.size() - 1)),
                    Flux.just(new PersistenceAcknowledgedEvent(marker, persisted)),
                    persisted.asMono().thenMany(Flux.defer(() -> runtime)));
        });
    }

    private RunEventPipelineContext pipelineContext(
            RuntimePlan plan,
            RunExecutionClaim executionClaim) {
        StandardRunInputPreparer.PreparedRun prepared = plan.prepared();
        return new RunEventPipelineContext(
                prepared.user(),
                prepared.session(),
                plan.admission().messagePlan(),
                plan.routeRef(),
                plan.bindingRef(),
                plan.assistant(),
                prepared.runId(),
                executionClaim,
                plan.pendingInteractionPayloadRef(),
                null,
                prepared.startAttempt(),
                documentIds(prepared.documents()),
                plan.deferredDomainAgentBindingRef(),
                plan.pendingRouteMemoryDecisionRef());
    }

    private ChatCommand commandForExecution(
            ChatCommand normalized,
            ChatRunMessagePlan messagePlan,
            IntentExpertScope intentExpertScope) {
        ChatMessage userMessage = messagePlan.userMessage();
        return new ChatCommand(
                normalized.commandId(),
                normalized.tenantId(),
                normalized.userId(),
                normalized.sessionId(),
                normalized.conversationId(),
                normalized.channel(),
                userMessage.content(),
                normalized.attachments(),
                normalized.metadata(),
                normalized.targetType(),
                normalized.targetId(),
                messagePlan.runMode(),
                messagePlan.parentMessageId(),
                normalized.editedMessageId(),
                normalized.regeneratedMessageId(),
                normalized.routeTrigger(),
                normalized.interactionId(),
                normalized.approved(),
                normalized.scope(),
                normalized.questionnaireAnswers(),
                normalized.appId(),
                normalized.appName(),
                normalized.agentMode(),
                normalized.interactionAction(),
                normalized.language(),
                intentExpertScope == null
                        ? normalized.intentAccessName()
                        : intentExpertScope.intentAccessName(),
                intentExpertScope);
    }

    private String runtimeBindingLeafId(ChatRunMessagePlan messagePlan) {
        return switch (messagePlan.runMode()) {
            case NEXT -> messagePlan.parentMessageId();
            case EDIT_USER, REGENERATE_ASSISTANT -> messagePlan.userMessage().id();
            case CONTINUE_INTERACTION ->
                    throw new IllegalArgumentException(
                            "CONTINUE_INTERACTION 不参与 RuntimeBinding leaf 计算");
        };
    }

    private List<String> documentIds(
            List<com.huawei.it.ex.one.domain.document.UploadedDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        for (com.huawei.it.ex.one.domain.document.UploadedDocument document : documents) {
            if (document != null
                    && document.id() != null
                    && !document.id().isBlank()) {
                ids.putIfAbsent(document.id(), Boolean.TRUE);
            }
        }
        return List.copyOf(ids.keySet());
    }

    record RuntimePlan(
            StandardRunInputPreparer.PreparedRun prepared,
            StandardRunAdmissionCoordinator.Admission admission,
            ChatCommand runCommand,
            String currentRouteQuery,
            String intentQuery,
            String runtimeBindingLeafId,
            AtomicReference<RuntimeBinding> bindingRef,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
            AssistantAssembly assistant,
            RuntimeBindingDispatchLifecycle bindingLifecycle,
            AtomicReference<DeferredDomainAgentBinding> deferredDomainAgentBindingRef,
            AtomicReference<PendingRouteMemoryDecision> pendingRouteMemoryDecisionRef
    ) {
    }
}
