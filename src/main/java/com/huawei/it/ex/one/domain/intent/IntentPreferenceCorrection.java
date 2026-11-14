/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.intent;

import java.time.Instant;

/** Persisted user correction for a previously selected Intent. */
public record IntentPreferenceCorrection(
        String id,
        String tenantId,
        String userId,
        String intentAccessName,
        String sessionId,
        String sourceMessageId,
        String sourceType,
        String queryText,
        String preferenceIntent,
        String preferenceIntentId,
        String originalIntent,
        Instant createdAt,
        Instant updatedAt
) {
    public IntentPreferenceCorrection {
        preferenceIntentId = optionalIntentId(preferenceIntentId);
    }

    public IntentPreferenceCorrection(String id, String tenantId, String userId, String intentAccessName,
            String sessionId, String sourceMessageId, String sourceType, String queryText,
            String preferenceIntent, String originalIntent, Instant createdAt, Instant updatedAt) {
        this(id, tenantId, userId, intentAccessName, sessionId, sourceMessageId, sourceType, queryText,
                preferenceIntent, null, originalIntent, createdAt, updatedAt);
    }

    /** Invalid optional identity must not reject an otherwise valid preference or truncate an ID. */
    public static String optionalIntentId(Object value) {
        return value instanceof String id && !id.isBlank() && id.codePointCount(0, id.length()) <= 128
                ? id : null;
    }
}
