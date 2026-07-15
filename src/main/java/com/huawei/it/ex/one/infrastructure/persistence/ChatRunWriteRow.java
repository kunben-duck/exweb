package com.huawei.it.ex.one.infrastructure.persistence;

import java.time.Instant;

/**
 * fin_ex_chat_run_t 写入参数。
 */
record ChatRunWriteRow(
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
        String metadataJson,
        Instant createdAt,
        Instant updatedAt
) {
}
