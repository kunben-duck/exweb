/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.intent;

import com.huawei.it.ex.one.domain.intent.IntentFeedback;

import java.util.List;
import java.util.Optional;

/** Database-only immutable feedback storage. */
public interface IntentFeedbackRepository {
    void insert(IntentFeedback feedback);

    Optional<IntentFeedback> findByOwnerAndRun(String tenantId, String userId, String runId);

    List<IntentFeedback> findByOwnerAndRuns(String tenantId, String userId, List<String> runIds);
}
