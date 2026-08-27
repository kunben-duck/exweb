package com.huawei.it.ex.one.infrastructure.persistence;

import java.time.Instant;

/** MyBatis write row for fin_ex_intent_preference_correction_t. */
public record IntentPreferenceCorrectionWriteRow(
        String id,
        String tenantId,
        String userId,
        String intentAccessName,
        String sessionId,
        String sourceMessageId,
        String sourceType,
        String queryText,
        String preferenceIntent,
        String originalIntent,
        Instant createdAt,
        Instant updatedAt
) {
}
