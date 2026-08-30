package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeInteractionDispatchState;
import com.huawei.it.ex.one.application.service.runtime.DeferredDomainAgentBinding;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable inputs and run-scoped atomic references shared by the event and completion pipeline. */
record RunEventPipelineContext(
        UserContext user,
        ChatSession session,
        ChatRunMessagePlan messagePlan,
        AtomicReference<RouteTarget> routeRef,
        AtomicReference<RuntimeBinding> bindingRef,
        AssistantAssembly assistant,
        String runId,
        RunExecutionClaim executionClaim,
        AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
        ChatInteractionRequest continuationInteractionRequest,
        RunStartAttempt startAttempt,
        List<String> intentClarificationDocumentIds,
        RuntimeInteractionDispatchState interactionDispatchState,
        AtomicBoolean asyncRunningObserved,
        AtomicReference<DeferredDomainAgentBinding> deferredDomainAgentBindingRef,
        AtomicReference<PendingRouteMemoryDecision> pendingRouteMemoryDecisionRef,
        AtomicReference<PendingRouteSwitchAppliedEvent> pendingRouteSwitchAppliedEventRef
) {
    RunEventPipelineContext {
        intentClarificationDocumentIds = intentClarificationDocumentIds == null
                ? List.of()
                : List.copyOf(intentClarificationDocumentIds);
        interactionDispatchState = interactionDispatchState == null
                ? RuntimeInteractionDispatchState.untracked()
                : interactionDispatchState;
        asyncRunningObserved = asyncRunningObserved == null ? new AtomicBoolean() : asyncRunningObserved;
        deferredDomainAgentBindingRef = deferredDomainAgentBindingRef == null
                ? new AtomicReference<>()
                : deferredDomainAgentBindingRef;
        pendingRouteMemoryDecisionRef = pendingRouteMemoryDecisionRef == null
                ? new AtomicReference<>()
                : pendingRouteMemoryDecisionRef;
        pendingRouteSwitchAppliedEventRef = pendingRouteSwitchAppliedEventRef == null
                ? new AtomicReference<>()
                : pendingRouteSwitchAppliedEventRef;
    }

    RunEventPipelineContext(
            UserContext user,
            ChatSession session,
            ChatRunMessagePlan messagePlan,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AssistantAssembly assistant,
            String runId,
            RunExecutionClaim executionClaim,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
            ChatInteractionRequest continuationInteractionRequest,
            RunStartAttempt startAttempt,
            List<String> intentClarificationDocumentIds) {
        this(user, session, messagePlan, routeRef, bindingRef, assistant, runId, executionClaim,
                pendingInteractionPayloadRef, continuationInteractionRequest, startAttempt,
                intentClarificationDocumentIds, RuntimeInteractionDispatchState.untracked(), new AtomicBoolean(),
                new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
    }

    RunEventPipelineContext(
            UserContext user,
            ChatSession session,
            ChatRunMessagePlan messagePlan,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AssistantAssembly assistant,
            String runId,
            RunExecutionClaim executionClaim,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
            ChatInteractionRequest continuationInteractionRequest,
            RunStartAttempt startAttempt,
            List<String> intentClarificationDocumentIds,
            AtomicReference<DeferredDomainAgentBinding> deferredDomainAgentBindingRef) {
        this(user, session, messagePlan, routeRef, bindingRef, assistant, runId, executionClaim,
                pendingInteractionPayloadRef, continuationInteractionRequest, startAttempt,
                intentClarificationDocumentIds, RuntimeInteractionDispatchState.untracked(), new AtomicBoolean(),
                deferredDomainAgentBindingRef, new AtomicReference<>(), new AtomicReference<>());
    }

    RunEventPipelineContext(
            UserContext user,
            ChatSession session,
            ChatRunMessagePlan messagePlan,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AssistantAssembly assistant,
            String runId,
            RunExecutionClaim executionClaim,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
            ChatInteractionRequest continuationInteractionRequest,
            RunStartAttempt startAttempt,
            List<String> intentClarificationDocumentIds,
            AtomicReference<DeferredDomainAgentBinding> deferredDomainAgentBindingRef,
            AtomicReference<PendingRouteMemoryDecision> pendingRouteMemoryDecisionRef) {
        this(user, session, messagePlan, routeRef, bindingRef, assistant, runId, executionClaim,
                pendingInteractionPayloadRef, continuationInteractionRequest, startAttempt,
                intentClarificationDocumentIds, RuntimeInteractionDispatchState.untracked(), new AtomicBoolean(),
                deferredDomainAgentBindingRef, pendingRouteMemoryDecisionRef, new AtomicReference<>());
    }

    RunEventPipelineContext(
            UserContext user,
            ChatSession session,
            ChatRunMessagePlan messagePlan,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AssistantAssembly assistant,
            String runId,
            RunExecutionClaim executionClaim,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
            ChatInteractionRequest continuationInteractionRequest,
            RunStartAttempt startAttempt,
            List<String> intentClarificationDocumentIds,
            AtomicReference<DeferredDomainAgentBinding> deferredDomainAgentBindingRef,
            AtomicReference<PendingRouteMemoryDecision> pendingRouteMemoryDecisionRef,
            AtomicReference<PendingRouteSwitchAppliedEvent> pendingRouteSwitchAppliedEventRef) {
        this(user, session, messagePlan, routeRef, bindingRef, assistant, runId, executionClaim,
                pendingInteractionPayloadRef, continuationInteractionRequest, startAttempt,
                intentClarificationDocumentIds, RuntimeInteractionDispatchState.untracked(), new AtomicBoolean(),
                deferredDomainAgentBindingRef, pendingRouteMemoryDecisionRef, pendingRouteSwitchAppliedEventRef);
    }

    RunEventPipelineContext(
            UserContext user,
            ChatSession session,
            ChatRunMessagePlan messagePlan,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AssistantAssembly assistant,
            String runId,
            RunExecutionClaim executionClaim,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
            ChatInteractionRequest continuationInteractionRequest,
            RunStartAttempt startAttempt,
            List<String> intentClarificationDocumentIds,
            RuntimeInteractionDispatchState interactionDispatchState) {
        this(user, session, messagePlan, routeRef, bindingRef, assistant, runId, executionClaim,
                pendingInteractionPayloadRef, continuationInteractionRequest, startAttempt,
                intentClarificationDocumentIds, interactionDispatchState, new AtomicBoolean(),
                new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
    }

    RunEventPipelineContext(
            UserContext user,
            ChatSession session,
            ChatRunMessagePlan messagePlan,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AssistantAssembly assistant,
            String runId,
            RunExecutionClaim executionClaim,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
            ChatInteractionRequest continuationInteractionRequest,
            RunStartAttempt startAttempt,
            List<String> intentClarificationDocumentIds,
            RuntimeInteractionDispatchState interactionDispatchState,
            AtomicBoolean asyncRunningObserved) {
        this(user, session, messagePlan, routeRef, bindingRef, assistant, runId, executionClaim,
                pendingInteractionPayloadRef, continuationInteractionRequest, startAttempt,
                intentClarificationDocumentIds, interactionDispatchState, asyncRunningObserved,
                new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
    }

    RunEventPipelineContext(
            UserContext user,
            ChatSession session,
            ChatRunMessagePlan messagePlan,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AssistantAssembly assistant,
            String runId,
            RunExecutionClaim executionClaim,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
            ChatInteractionRequest continuationInteractionRequest,
            RunStartAttempt startAttempt,
            List<String> intentClarificationDocumentIds,
            RuntimeInteractionDispatchState interactionDispatchState,
            AtomicBoolean asyncRunningObserved,
            AtomicReference<DeferredDomainAgentBinding> deferredDomainAgentBindingRef) {
        this(user, session, messagePlan, routeRef, bindingRef, assistant, runId, executionClaim,
                pendingInteractionPayloadRef, continuationInteractionRequest, startAttempt,
                intentClarificationDocumentIds, interactionDispatchState, asyncRunningObserved,
                deferredDomainAgentBindingRef, new AtomicReference<>(), new AtomicReference<>());
    }
}
