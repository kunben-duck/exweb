/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import com.huawei.it.ex.one.domain.intent.IntentFeedback;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/** MyBatis statements for immutable per-run Intent feedback. */
@Mapper
public interface IntentFeedbackMapper {
    /**
     * Insert once, enforcing owner/run uniqueness.
     * @param feedback normalized feedback
     */
    void insert(IntentFeedback feedback);

    /**
     * Read one owned run evaluation.
     * @param tenantId tenant
     * @param userId owner
     * @param runId source run
     * @return existing evaluation
     */
    Optional<IntentFeedback> findByOwnerAndRun(
            @Param("tenantId") String tenantId, @Param("userId") String userId, @Param("runId") String runId);

    /**
     * Read a bounded batch of owned evaluations.
     * @param tenantId tenant
     * @param userId owner
     * @param runIds source runs
     * @return matching evaluations
     */
    List<IntentFeedback> findByOwnerAndRuns(
            @Param("tenantId") String tenantId, @Param("userId") String userId,
            @Param("runIds") List<String> runIds);
}
