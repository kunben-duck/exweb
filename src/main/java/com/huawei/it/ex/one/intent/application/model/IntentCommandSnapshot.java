package com.huawei.it.ex.one.intent.application.model;

import java.util.Map;

/** Immutable command fields consumed by the intent context. */
public record IntentCommandSnapshot(
        String commandId,
        String tenantId,
        String userId,
        String sessionId,
        String message,
        Map<String, Object> metadata,
        String routeTrigger
) {
    public IntentCommandSnapshot {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public IntentCommandSnapshot withMessage(String nextMessage) {
        return new IntentCommandSnapshot(commandId, tenantId, userId, sessionId, nextMessage, metadata, routeTrigger);
    }
}
