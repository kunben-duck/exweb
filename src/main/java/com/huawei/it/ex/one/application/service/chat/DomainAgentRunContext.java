package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable inputs and mutable routing references for one DomainAgent refusal flow. */
record DomainAgentRunContext(
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
        AgentDataPersistenceState persistenceState
) {
    DomainAgentRunContext {
        persistenceState = persistenceState == null
                ? AgentDataPersistenceState.full()
                : persistenceState;
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
        this(command, runId, session, memory, route, user, routeRef, bindingRef, executionClaim,
                forwardHeaders, traceContext, intentDecision, documents, rejectedDomainAgentIds,
                rerouteCount, routeMemoryQuery, AgentDataPersistenceState.full());
    }
}
