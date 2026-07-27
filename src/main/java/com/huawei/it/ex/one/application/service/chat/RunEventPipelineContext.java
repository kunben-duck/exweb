package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import java.util.List;
import java.util.Map;
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
        List<String> intentClarificationDocumentIds
) {
    RunEventPipelineContext {
        intentClarificationDocumentIds = intentClarificationDocumentIds == null
                ? List.of()
                : List.copyOf(intentClarificationDocumentIds);
    }
}
