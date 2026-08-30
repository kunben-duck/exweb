package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.runtime.DeferredDomainAgentBinding;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable inputs and mutable routing references for one DomainAgent refusal flow. */
record DomainAgentRunContext(
        ChatCommand command,
        String runId,
        String userMessageId,
        ChatSession session,
        MemoryContext memory,
        RouteTarget route,
        UserContext user,
        AtomicReference<RouteTarget> routeRef,
        AtomicReference<RuntimeBinding> bindingRef,
        RunExecutionClaim executionClaim,
        RuntimeForwardHeaders forwardHeaders,
        TraceContext traceContext,
        IntentDecision intentDecision,
        List<UploadedDocument> documents,
        Set<String> rejectedDomainAgentIds,
        int rerouteCount,
        String routeMemoryQuery,
        AgentDataPersistenceState persistenceState,
        MessageSkillTracker messageSkill,
        AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
        AtomicReference<DeferredDomainAgentBinding> deferredDomainAgentBindingRef,
        AtomicReference<PendingRouteMemoryDecision> pendingRouteMemoryDecisionRef
) {
    DomainAgentRunContext {
        userMessageId = userMessageId == null || userMessageId.isBlank() ? null : userMessageId.trim();
        persistenceState = persistenceState == null
                ? AgentDataPersistenceState.full()
                : persistenceState;
        messageSkill = messageSkill == null ? new MessageSkillTracker() : messageSkill;
        pendingInteractionPayloadRef = pendingInteractionPayloadRef == null
                ? new AtomicReference<>()
                : pendingInteractionPayloadRef;
        deferredDomainAgentBindingRef = deferredDomainAgentBindingRef == null
                ? new AtomicReference<>()
                : deferredDomainAgentBindingRef;
        pendingRouteMemoryDecisionRef = pendingRouteMemoryDecisionRef == null
                ? new AtomicReference<>()
                : pendingRouteMemoryDecisionRef;
    }

    DomainAgentRunContext(
            ChatCommand command,
            String runId,
            ChatSession session,
            MemoryContext memory,
            RouteTarget route,
            UserContext user,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            RunExecutionClaim executionClaim,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            IntentDecision intentDecision,
            List<UploadedDocument> documents,
            Set<String> rejectedDomainAgentIds,
            int rerouteCount,
            String routeMemoryQuery,
            AgentDataPersistenceState persistenceState) {
        this(command, runId, null, session, memory, route, user, routeRef, bindingRef, executionClaim,
                forwardHeaders, traceContext, intentDecision, documents, rejectedDomainAgentIds,
                rerouteCount, routeMemoryQuery, persistenceState, new MessageSkillTracker(),
                new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
    }

    DomainAgentRunContext(
            ChatCommand command,
            String runId,
            ChatSession session,
            MemoryContext memory,
            RouteTarget route,
            UserContext user,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            RunExecutionClaim executionClaim,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            IntentDecision intentDecision,
            List<UploadedDocument> documents,
            Set<String> rejectedDomainAgentIds,
            int rerouteCount,
            String routeMemoryQuery) {
        this(command, runId, null, session, memory, route, user, routeRef, bindingRef, executionClaim,
                forwardHeaders, traceContext, intentDecision, documents, rejectedDomainAgentIds,
                rerouteCount, routeMemoryQuery, AgentDataPersistenceState.full(),
                new MessageSkillTracker(), new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>());
    }

    DomainAgentRunContext(
            ChatCommand command,
            String runId,
            String userMessageId,
            ChatSession session,
            MemoryContext memory,
            RouteTarget route,
            UserContext user,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            RunExecutionClaim executionClaim,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            IntentDecision intentDecision,
            List<UploadedDocument> documents,
            Set<String> rejectedDomainAgentIds,
            int rerouteCount,
            String routeMemoryQuery,
            AgentDataPersistenceState persistenceState,
            MessageSkillTracker messageSkill,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef) {
        this(command, runId, userMessageId, session, memory, route, user, routeRef, bindingRef,
                executionClaim, forwardHeaders, traceContext, intentDecision, documents,
                rejectedDomainAgentIds, rerouteCount, routeMemoryQuery, persistenceState, messageSkill,
                pendingInteractionPayloadRef, new AtomicReference<>(), new AtomicReference<>());
    }

    DomainAgentRunContext(
            ChatCommand command,
            String runId,
            String userMessageId,
            ChatSession session,
            MemoryContext memory,
            RouteTarget route,
            UserContext user,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            RunExecutionClaim executionClaim,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            IntentDecision intentDecision,
            List<UploadedDocument> documents,
            Set<String> rejectedDomainAgentIds,
            int rerouteCount,
            String routeMemoryQuery,
            AgentDataPersistenceState persistenceState,
            MessageSkillTracker messageSkill,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
            AtomicReference<DeferredDomainAgentBinding> deferredDomainAgentBindingRef) {
        this(command, runId, userMessageId, session, memory, route, user, routeRef, bindingRef,
                executionClaim, forwardHeaders, traceContext, intentDecision, documents,
                rejectedDomainAgentIds, rerouteCount, routeMemoryQuery, persistenceState, messageSkill,
                pendingInteractionPayloadRef, deferredDomainAgentBindingRef, new AtomicReference<>());
    }
}
