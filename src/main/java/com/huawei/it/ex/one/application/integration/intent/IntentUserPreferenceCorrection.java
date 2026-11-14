/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.intent;

import com.huawei.it.ex.one.domain.intent.IntentPreferenceCorrection;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** A trusted user routing preference sent to the Intent service. */
public record IntentUserPreferenceCorrection(
        String query,
        String preferenceIntent,
        @JsonInclude(JsonInclude.Include.NON_NULL) String preferenceIntentId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String originalIntent,
        Instant timestamp
) {
    public IntentUserPreferenceCorrection {
        preferenceIntentId = IntentPreferenceCorrection.optionalIntentId(preferenceIntentId);
    }

    public IntentUserPreferenceCorrection(String query, String preferenceIntent, String originalIntent,
            Instant timestamp) {
        this(query, preferenceIntent, null, originalIntent, timestamp);
    }
}
