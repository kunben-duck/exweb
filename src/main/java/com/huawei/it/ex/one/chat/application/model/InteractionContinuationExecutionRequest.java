package com.huawei.it.ex.one.chat.application.model;

import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.chat.application.service.ChatInteractionClaimResult;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.security.domain.UserContext;

/** Immutable handoff from Interaction admission to the existing continuation state machine. */
public record InteractionContinuationExecutionRequest(
        UserContext user,
        ChatInteractionClaimResult claim,
        String runId,
        RuntimeForwardHeaders forwardHeaders,
        TraceContext traceContext,
        RunStartAttempt startAttempt,
        IntentClarificationContinuationInput clarificationInput
) {
}
