package com.huawei.it.ex.one.chat.application.model;

import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
import com.huawei.it.ex.one.intent.application.model.IntentDecision;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable inputs and mutable routing references for one DomainAgent refusal flow. */
public record DomainAgentRefusalRunContext(
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
}
