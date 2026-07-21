package com.huawei.it.ex.one.chat.infrastructure.persistence;

import java.time.Instant;

/**
 * fin_ex_chat_run_execution_t 写入参数对象。
 *
 * <p>执行控制面字段较多，使用显式 row 对象避免 Mapper 暴露大段散参。</p>
 */
public record ChatRunExecutionWriteRow(
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
        String metadataJson,
        Instant createdAt,
        Instant updatedAt
) {
}
