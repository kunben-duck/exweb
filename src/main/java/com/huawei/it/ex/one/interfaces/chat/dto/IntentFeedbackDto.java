/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import com.huawei.it.ex.one.domain.intent.IntentFeedback;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Public feedback view; never exposes ownership or the source question. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntentFeedbackDto(
        String runId, String feedbackType, String commentText, String replacementRunId,
        String skillId, ChatSelectedIntentDto selectedIntent, String intentAccessName, Instant createdAt) {
    public static IntentFeedbackDto from(IntentFeedback value) {
        return value == null ? null : new IntentFeedbackDto(
                value.runId(), value.feedbackType(), value.commentText(), value.replacementRunId(),
                value.skillId(), value.intentName() == null ? null
                        : new ChatSelectedIntentDto(value.intentId(), value.intentName()),
                value.intentAccessName(), value.createdAt());
    }
}
