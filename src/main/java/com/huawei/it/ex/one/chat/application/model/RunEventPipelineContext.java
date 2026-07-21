package com.huawei.it.ex.one.chat.application.model;

import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatRunMessagePlan;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 已有 run 事件管线的不可变输入与同 run 原子引用集合。
 *
 * <p>该类型只承接原主服务中的同名 record，字段、构造顺序和引用语义保持不变。</p>
 */
public record RunEventPipelineContext(
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
        List<String> intentClarificationDocumentIds
) {
    public RunEventPipelineContext {
        intentClarificationDocumentIds = intentClarificationDocumentIds == null
                ? List.of()
                : List.copyOf(intentClarificationDocumentIds);
    }
}
