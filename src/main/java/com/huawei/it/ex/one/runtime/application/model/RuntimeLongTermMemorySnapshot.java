package com.huawei.it.ex.one.runtime.application.model;

import java.time.Instant;

/** Long-term memory item visible to a Runtime. */
public record RuntimeLongTermMemorySnapshot(
        String id,
        String tenantId,
        String userId,
        String memoryType,
        String content,
        double confidence,
        Instant createdAt
) {
}
