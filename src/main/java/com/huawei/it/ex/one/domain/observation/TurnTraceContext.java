/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.observation;

import java.time.Instant;

/** 服务端可信问答关联；单调时钟只允许由生成它的进程使用。 */
public record TurnTraceContext(
        String traceId, String tenantId, String userId, String sessionId,
        String rootRunId, String rootUserMessageId, String sourceRunId, String sourceTraceId,
        String originType, String runId, String userMessageId, long runOrdinal, Instant startedAt,
        Instant firstBusinessFrameAt, Long firstBusinessFrameLatencyMs, boolean refusal,
        String parentObservationId, boolean retainText, String clockInstanceId, Long startedNanos
) {
    public TurnTraceContext admitted(String session, String message) {
        return new TurnTraceContext(traceId, tenantId, userId, session, rootRunId,
                rootUserMessageId == null ? message : rootUserMessageId, sourceRunId, sourceTraceId,
                originType, runId, message, Math.max(1, runOrdinal), startedAt,
                firstBusinessFrameAt, firstBusinessFrameLatencyMs, refusal, parentObservationId, retainText, clockInstanceId, startedNanos);
    }

    public TurnTraceContext sampled(Instant first, Long latency, boolean rejected, String parent) {
        boolean earlier = first != null && (firstBusinessFrameAt == null || first.isBefore(firstBusinessFrameAt));
        return new TurnTraceContext(traceId, tenantId, userId, sessionId, rootRunId, rootUserMessageId,
                sourceRunId, sourceTraceId, originType, runId, userMessageId, runOrdinal, startedAt,
                earlier ? first : firstBusinessFrameAt, earlier ? latency : firstBusinessFrameLatencyMs,
                refusal || rejected, parent == null ? parentObservationId : parent, retainText, clockInstanceId, startedNanos);
    }

    public TurnTraceContext withRetention(boolean retain) {
        return new TurnTraceContext(traceId, tenantId, userId, sessionId, rootRunId, rootUserMessageId,
                sourceRunId, sourceTraceId, originType, runId, userMessageId, runOrdinal, startedAt,
                firstBusinessFrameAt, firstBusinessFrameLatencyMs, refusal, parentObservationId, retain, clockInstanceId, startedNanos);
    }
}
