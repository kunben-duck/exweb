package com.huawei.it.ex.one.intent.application.model;

/** Input fields required to assemble optional intent memory. */
public record IntentMemoryRequest(
        String tenantId,
        String userId,
        String sessionId,
        String query
) {
}
