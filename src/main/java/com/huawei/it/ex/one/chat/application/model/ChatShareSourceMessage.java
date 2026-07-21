package com.huawei.it.ex.one.chat.application.model;

import java.time.Instant;
import java.util.List;

/** Immutable message projection exposed to the share context. */
public record ChatShareSourceMessage(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String parentMessageId,
        String role,
        String content,
        String runId,
        String metadataJson,
        List<ChatShareSourcePart> parts,
        Instant createdAt
) {
    public ChatShareSourceMessage {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
