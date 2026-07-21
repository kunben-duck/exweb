package com.huawei.it.ex.one.runtime.application.model;

import java.time.Instant;
import java.util.Map;

/** Chat run facts required by Runtime cancellation and recovery. */
public record RuntimeRunSnapshot(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String status,
        String routeType,
        String agentCode,
        String runtimeProvider,
        String runtimeSessionId,
        String runMode,
        String parentMessageId,
        String userMessageId,
        String assistantMessageId,
        Long firstSeq,
        Long lastSeq,
        String cancelReason,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public RuntimeRunSnapshot {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
