package com.huawei.it.ex.one.application.integration.intent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** A trusted user routing preference sent to the Intent service. */
public record IntentUserPreferenceCorrection(
        String query,
        String preferenceIntent,
        @JsonInclude(JsonInclude.Include.NON_NULL) String originalIntent,
        Instant timestamp
) {
}
