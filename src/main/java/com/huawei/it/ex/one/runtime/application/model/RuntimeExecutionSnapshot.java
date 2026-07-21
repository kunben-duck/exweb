package com.huawei.it.ex.one.runtime.application.model;

import java.time.Instant;
import java.util.Map;

/** Runtime recovery view of the Chat execution control plane. */
public record RuntimeExecutionSnapshot(
        String id,
        String runId,
        String tenantId,
        String userId,
        String sessionId,
        String executionStatus,
        String ownerInstanceId,
        Instant heartbeatAt,
        Instant leaseUntil,
        long fencingToken,
        String recoveryStrategy,
        String recoveredByInstanceId,
        int recoveryAttempts,
        Instant recoveryLeaseUntil,
        String runtimeResumeToken,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public RuntimeExecutionSnapshot {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
