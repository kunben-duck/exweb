/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.routing;

/** Normalized independent feedback input. Display summaries are user preference inputs, not route facts. */
public record IntentFeedbackCommand(
        String feedbackType, String commentText, String replacementRunId, String skillId,
        SelectedIntent selectedIntent, String intentAccessName) {
    public IntentFeedbackCommand {
        feedbackType = required(feedbackType, 32, "feedbackType");
        commentText = optional(commentText, Integer.MAX_VALUE, "commentText");
        replacementRunId = optional(replacementRunId, 64, "replacementRunId");
        skillId = optional(skillId, 128, "skillId");
        intentAccessName = optional(intentAccessName, 128, "intentAccessName");
        if (commentText != null && commentText.codePointCount(0, commentText.length()) > 1024) {
            throw new IllegalArgumentException("commentText must not exceed 1024 Unicode code points");
        }
        switch (feedbackType) {
            case "CORRECT" -> {
                if (commentText != null || replacementRunId != null || skillId != null) {
                    throw new IllegalArgumentException("CORRECT only accepts an optional selectedIntent");
                }
            }
            case "INCORRECT_COMMENT" -> {
                if (commentText == null || replacementRunId != null || skillId != null || selectedIntent != null) {
                    throw new IllegalArgumentException("INCORRECT_COMMENT requires only commentText");
                }
            }
            case "INCORRECT_SWITCH" -> {
                if (commentText != null || replacementRunId == null || skillId == null || selectedIntent == null) {
                    throw new IllegalArgumentException(
                            "INCORRECT_SWITCH requires replacementRunId, skillId and selectedIntent");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported feedbackType");
        }
    }

    public record SelectedIntent(String intentId, String intentName) {
        public SelectedIntent {
            intentId = optional(intentId, 128, "selectedIntent.intentId");
            intentName = required(intentName, 256, "selectedIntent.intentName");
        }
    }

    public static String required(String value, int max, String name) {
        String result = optional(value, max, name);
        if (result == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return result;
    }

    private static String optional(String value, int max, String name) {
        String result = value == null || value.isBlank() ? null : value.trim();
        if (result != null && result.length() > max) {
            throw new IllegalArgumentException(name + " exceeds maximum length " + max);
        }
        return result;
    }
}
