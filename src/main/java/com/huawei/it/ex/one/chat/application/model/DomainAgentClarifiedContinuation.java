package com.huawei.it.ex.one.chat.application.model;

import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import com.huawei.it.ex.one.intent.application.model.RouteSignalResult;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Inputs used when intent clarification resumes a DomainAgent refusal reroute. */
public record DomainAgentClarifiedContinuation(
        ChatCommand runCommand,
        String runId,
        ChatSession session,
        MemoryContext memory,
        UserContext user,
        AtomicReference<RouteTarget> routeRef,
        AtomicReference<RuntimeBinding> bindingRef,
        RunExecutionClaim executionClaim,
        RuntimeForwardHeaders forwardHeaders,
        TraceContext traceContext,
        List<UploadedDocument> documents,
        String routeMemoryQuery,
        String intentRouteMemoryQuery,
        Map<String, Object> runtimeMetadataOverride,
        RouteSignalResult routeSignal) {
}
