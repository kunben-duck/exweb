package com.huawei.it.ex.one.intent.application.model;

import java.time.Instant;

/** Immutable recent-message snapshot used by optional intent memory assembly. */
public record IntentMessageSnapshot(
        String id,
        String role,
        String content,
        Instant createdAt
) {
}
