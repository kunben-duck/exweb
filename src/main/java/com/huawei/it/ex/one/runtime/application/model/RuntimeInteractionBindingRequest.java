package com.huawei.it.ex.one.runtime.application.model;

/** Interaction facts required to resume the exact Runtime binding that created it. */
public record RuntimeInteractionBindingRequest(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String assistantMessageId,
        String runtimeBindingId,
        String runtimeSessionId
) {
}
