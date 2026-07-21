package com.huawei.it.ex.one.runtime.application.model;

import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.List;

/**
 * AgentRuntime 执行上下文。
 *
 * <p>该对象是 ChatService 到 Runtime 防腐层的应用级输入，不是 Relay wire 请求体。
 * Cookie 等转发头只保存在 {@link RuntimeForwardHeaders} 内存快照中，由 adapter 决定是否透传，
 * 不得写入事件、run metadata 或日志。</p>
 */
public record RuntimeExecutionContext(
        RuntimeCommandSnapshot command,
        String runId,
        RuntimeMemorySnapshot memory,
        RuntimeIntentSnapshot intent,
        RuntimeRouteSnapshot route,
        UserContext user,
        RuntimeBinding binding,
        RuntimeSessionMode runtimeSessionMode,
        RuntimeForwardHeaders forwardHeaders,
        List<RuntimeDocumentSnapshot> documents,
        TraceContext traceContext
) {
    public RuntimeExecutionContext {
        documents = documents == null ? List.of() : List.copyOf(documents);
        traceContext = traceContext == null ? TraceContext.empty() : traceContext;
    }

    public RuntimeExecutionContext(RuntimeCommandSnapshot command, String runId, RuntimeMemorySnapshot memory,
                                   RuntimeIntentSnapshot intent, RuntimeRouteSnapshot route, UserContext user,
                                   RuntimeBinding binding, RuntimeSessionMode runtimeSessionMode,
                                   RuntimeForwardHeaders forwardHeaders, List<RuntimeDocumentSnapshot> documents) {
        this(command, runId, memory, intent, route, user, binding, runtimeSessionMode, forwardHeaders, documents,
                TraceContext.empty());
    }

    public RuntimeExecutionContext(RuntimeCommandSnapshot command, String runId, RuntimeMemorySnapshot memory,
                                   RuntimeIntentSnapshot intent, RuntimeRouteSnapshot route, UserContext user,
                                   RuntimeBinding binding, RuntimeSessionMode runtimeSessionMode,
                                   RuntimeForwardHeaders forwardHeaders) {
        this(command, runId, memory, intent, route, user, binding, runtimeSessionMode, forwardHeaders, List.of(),
                TraceContext.empty());
    }
}
