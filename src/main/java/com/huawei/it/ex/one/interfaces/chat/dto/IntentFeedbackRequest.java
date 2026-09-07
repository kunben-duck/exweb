/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import com.huawei.it.ex.one.application.service.routing.IntentFeedbackCommand;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Independent per-run Intent feedback. Cross-field validation is performed before persistence. */
public record IntentFeedbackRequest(
        @NotBlank @Size(max = 32) String feedbackType,
        String commentText,
        @Size(max = 64) String replacementRunId,
        @Size(max = 128) String skillId,
        @Valid ChatSelectedIntentDto selectedIntent,
        @Size(max = 128) String intentAccessName) {
    public IntentFeedbackCommand toCommand() {
        return new IntentFeedbackCommand(feedbackType, commentText, replacementRunId, skillId,
                selectedIntent == null ? null : new IntentFeedbackCommand.SelectedIntent(
                        selectedIntent.intentId(), selectedIntent.intentName()), intentAccessName);
    }
}
