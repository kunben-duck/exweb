/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import java.time.Instant;

/** Lightweight recent-preference row used by Intent request enrichment. */
public record IntentPreferenceCorrectionReadRow(
        String queryText,
        String preferenceIntent,
        String preferenceIntentId,
        String originalIntent,
        Instant updatedAt
) {
    public IntentPreferenceCorrectionReadRow(String queryText, String preferenceIntent, String originalIntent,
            Instant updatedAt) {
        this(queryText, preferenceIntent, null, originalIntent, updatedAt);
    }
}
