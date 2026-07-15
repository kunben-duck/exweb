package com.huawei.it.ex.one.infrastructure.persistence;

import java.time.Instant;

/**
 * fin_ex_chat_event_t 写入参数。
 */
record ChatEventWriteRow(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        long seq,
        String eventType,
        String payloadJson,
        Instant createdAt
) {
}
