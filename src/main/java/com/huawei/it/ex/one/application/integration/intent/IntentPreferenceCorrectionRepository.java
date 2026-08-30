/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.intent;

import com.huawei.it.ex.one.domain.intent.IntentPreferenceCorrection;

import java.util.List;

/** Persistence boundary for cross-session Intent preference corrections. */
public interface IntentPreferenceCorrectionRepository {
    /** Inserts or replaces the correction identified by its owner, Intent entry, and source message. */
    void upsert(IntentPreferenceCorrection correction);

    /** Returns the latest corrections for the effective Intent entry, newest first. */
    List<IntentUserPreferenceCorrection> findRecent(
            String tenantId, String userId, String intentAccessName, int limit);
}
