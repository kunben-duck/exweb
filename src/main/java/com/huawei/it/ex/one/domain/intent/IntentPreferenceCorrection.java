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
        String originalIntent,
        Instant createdAt,
        Instant updatedAt
) {
}
