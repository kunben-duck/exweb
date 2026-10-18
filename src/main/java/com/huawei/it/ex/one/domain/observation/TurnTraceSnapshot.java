/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.observation;

import java.time.Instant;

/** 独立观测写入快照，不参与业务裁决。 */
public record TurnTraceSnapshot(
        String traceId,
        String tenantId,
        String userId,
        String sessionId,
        String rootRunId,
        String rootUserMessageId,
        String sourceRunId,
        String sourceTraceId,
        String currentRunId,
        String originType,
        long currentRunOrdinal,
        int stateVersion,
        String status,
        boolean isRefusal,
        Instant startedAt,
        Instant firstBusinessFrameAt,
        Long firstBusinessFrameLatencyMs,
        Instant finishedAt,
        Long durationMs,
        String errorCode,
        Instant createdAt,
        Instant updatedAt
) {
    /** 业务准入回滚时不发布尚未提交的消息归属或运行阶段。 */
    public TurnTraceSnapshot beforeAdmission() {
        return new TurnTraceSnapshot(traceId, tenantId, userId, sessionId, rootRunId, null,
                sourceRunId, sourceTraceId, currentRunId, originType, 0, stateVersion, status,
                isRefusal, startedAt, firstBusinessFrameAt, firstBusinessFrameLatencyMs, finishedAt,
                durationMs, errorCode, createdAt, updatedAt);
    }

    public TurnTraceSnapshot withSourceTrace(String source) {
        return new TurnTraceSnapshot(traceId, tenantId, userId, sessionId, rootRunId, rootUserMessageId,
                sourceRunId, source, currentRunId, originType, currentRunOrdinal, stateVersion, status,
                isRefusal, startedAt, firstBusinessFrameAt, firstBusinessFrameLatencyMs, finishedAt,
                durationMs, errorCode, createdAt, updatedAt);
    }
}
