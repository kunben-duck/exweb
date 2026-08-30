/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.intent;

import java.util.Map;

/**
 * Stable application frame produced by the IntentDecision streaming client.
 *
 * @param type process or terminal frame type.
 * @param payload normalized process payload.
 * @param recognitionResult final recognition result; only present for {@link Type#RESULT}.
 * @param attempt current HTTP attempt, starting at one.
 * @param maxAttempts maximum attempts for this invocation.
 */
public record IntentDecisionStreamFrame(
        Type type,
        Map<String, Object> payload,
        IntentRecognitionResult recognitionResult,
        int attempt,
        int maxAttempts
) {
    public enum Type {
        PROGRESS,
        DELTA,
        RESULT
    }

    public IntentDecisionStreamFrame {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        attempt = Math.max(1, attempt);
        maxAttempts = Math.max(attempt, maxAttempts);
    }

    public static IntentDecisionStreamFrame progress(Map<String, Object> payload, int attempt, int maxAttempts) {
        return new IntentDecisionStreamFrame(Type.PROGRESS, payload, null, attempt, maxAttempts);
    }

    public static IntentDecisionStreamFrame delta(Map<String, Object> payload, int attempt, int maxAttempts) {
        return new IntentDecisionStreamFrame(Type.DELTA, payload, null, attempt, maxAttempts);
    }

    public static IntentDecisionStreamFrame result(IntentRecognitionResult result, int attempt, int maxAttempts) {
        return new IntentDecisionStreamFrame(Type.RESULT, Map.of(), result, attempt, maxAttempts);
    }
}
