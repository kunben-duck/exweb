/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.intent;

/** Stable error contract for the independent feedback endpoints. */
public class IntentFeedbackException extends RuntimeException {
    private final String code;

    private IntentFeedbackException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static IntentFeedbackException conflict() {
        return new IntentFeedbackException("INTENT_FEEDBACK_ALREADY_SUBMITTED",
                "This run already has a different Intent feedback submission", null);
    }

    public static IntentFeedbackException unavailable(Throwable cause) {
        return new IntentFeedbackException("INTENT_FEEDBACK_UNAVAILABLE",
                "Intent feedback is temporarily unavailable; retry this request only", cause);
    }
}
