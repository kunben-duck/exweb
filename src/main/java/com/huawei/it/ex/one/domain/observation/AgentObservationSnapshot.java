/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.observation;

import java.time.Instant;

/** 独立观测写入快照，不参与业务裁决。 */
public record AgentObservationSnapshot(
        String observationId,
        String traceId,
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String userMessageId,
        String parentObservationId,
        String retryOfObservationId,
        int attemptNo,
        String agentType,
        String agentMode,
        String operation,
        String skillId,
        String runtimeSessionId,
        String status,
        String outcome,
        Instant startedAt,
        Instant firstBusinessFrameAt,
        Long firstBusinessFrameLatencyMs,
        Instant finishedAt,
        Long durationMs,
        String rejectCode,
        String rejectReasonCode,
        String rejectMessage,
        Boolean rejectRecoverable,
        Instant createdAt,
        Instant updatedAt
) { }
