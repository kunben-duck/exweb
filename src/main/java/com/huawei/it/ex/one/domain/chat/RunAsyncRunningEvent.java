/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/** DomainAgent 已将当前 run 转入后台执行。 */
public record RunAsyncRunningEvent(
        String runId,
        String sessionId,
        long sequence,
        Instant createdAt,
        Map<String, Object> payload
) implements ChatEvent {
    public static RunAsyncRunningEvent of(String runId, String sessionId, String message) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", "agent.async_started");
        payload.put("status", "ASYNC_RUNNING");
        if (message != null) {
            payload.put("message", message);
        }
        return new RunAsyncRunningEvent(
                runId, sessionId, 0L, Instant.now(), ChatPayloadMaps.immutableCopy(payload));
    }

    public RunAsyncRunningEvent withTaskContext(String assistantMessageId, Instant expiresAt) {
        Map<String, Object> next = new java.util.LinkedHashMap<>(payload);
        next.put("messageReady", true);
        next.put("assistantMessageId", assistantMessageId);
        next.put("feedbackTargetMessageId", assistantMessageId);
        next.put("expiresAt", expiresAt.toString());
        return new RunAsyncRunningEvent(
                runId, sessionId, sequence, createdAt, ChatPayloadMaps.immutableCopy(next));
    }

    @Override
    public String type() {
        return "run.async_running";
    }
}
