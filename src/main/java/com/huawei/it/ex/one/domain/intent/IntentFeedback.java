/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.intent;

import java.time.Instant;
import java.util.Objects;

/** Immutable user evaluation of one run, independent of mutable preference snapshots. */
public record IntentFeedback(
        String id, String tenantId, String userId, String sessionId, String runId,
        String sourceMessageId, String intentAccessName, String feedbackType, String commentText,
        String replacementRunId, String skillId, String intentId, String intentName, Instant createdAt) {
    public boolean sameSubmission(IntentFeedback other) {
        return Objects.equals(intentAccessName, other.intentAccessName)
                && Objects.equals(feedbackType, other.feedbackType)
                && Objects.equals(commentText, other.commentText)
                && Objects.equals(replacementRunId, other.replacementRunId)
                && Objects.equals(skillId, other.skillId)
                && Objects.equals(intentId, other.intentId)
                && Objects.equals(intentName, other.intentName);
    }
}
