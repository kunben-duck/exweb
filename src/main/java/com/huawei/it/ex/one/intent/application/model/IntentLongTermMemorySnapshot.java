package com.huawei.it.ex.one.intent.application.model;

import java.time.Instant;

/** Immutable long-term memory value exposed by the Intent application boundary. */
public record IntentLongTermMemorySnapshot(
        String id,
        String tenantId,
        String userId,
        String memoryType,
        String content,
        double confidence,
        Instant createdAt
) {
}
