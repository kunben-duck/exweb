package com.huawei.it.ex.one.infrastructure.persistence;

import java.time.Instant;

/** Lightweight recent-preference row used by Intent request enrichment. */
public record IntentPreferenceCorrectionReadRow(
        String queryText,
        String preferenceIntent,
        String originalIntent,
        Instant updatedAt
) {
}
