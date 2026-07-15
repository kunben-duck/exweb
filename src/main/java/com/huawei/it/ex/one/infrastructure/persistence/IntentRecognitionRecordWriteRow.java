package com.huawei.it.ex.one.infrastructure.persistence;

import java.time.Instant;

/**
 * fin_ex_intent_recognition_t 写入参数对象。
 */
public record IntentRecognitionRecordWriteRow(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String commandId,
        String queryText,
        String queryHash,
        String status,
        String intentId,
        String intentName,
        String resourceId,
        Double confidence,
        String source,
        Integer candidateCount,
        Double confidenceThreshold,
        Boolean accepted,
        String routeType,
        String routeAgentCode,
        String routeReason,
        String resultMessage,
        String itemsJson,
        String rawResponseJson,
        String errorMessage,
        Long latencyMs,
        Instant createdAt
) {
}
