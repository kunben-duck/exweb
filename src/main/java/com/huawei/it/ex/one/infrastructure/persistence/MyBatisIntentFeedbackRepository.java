/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import com.huawei.it.ex.one.application.integration.intent.IntentFeedbackRepository;
import com.huawei.it.ex.one.domain.intent.IntentFeedback;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Keeps feedback and preference writes on the existing transaction-bound datasource. */
@Repository
public class MyBatisIntentFeedbackRepository implements IntentFeedbackRepository {
    private final IntentFeedbackMapper mapper;

    public MyBatisIntentFeedbackRepository(IntentFeedbackMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(IntentFeedback feedback) {
        mapper.insert(feedback);
    }

    @Override
    public Optional<IntentFeedback> findByOwnerAndRun(String tenantId, String userId, String runId) {
        return mapper.findByOwnerAndRun(tenantId, userId, runId);
    }

    @Override
    public List<IntentFeedback> findByOwnerAndRuns(String tenantId, String userId, List<String> runIds) {
        return runIds.isEmpty() ? List.of() : mapper.findByOwnerAndRuns(tenantId, userId, runIds);
    }
}
