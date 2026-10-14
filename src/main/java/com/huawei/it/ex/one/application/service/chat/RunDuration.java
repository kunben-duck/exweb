/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;
import com.huawei.it.ex.one.domain.chat.RunCancelledEvent;
import com.huawei.it.ex.one.domain.chat.RunCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunWaitingUserEvent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 同一终态提交共享一个耗时快照，不累计 Interaction 的前序 Run，也不依赖客户端时间。 */
record RunDuration(Long millis) {
    private static final String KEY = "runDurationMs";
    private static final Set<String> TERMINAL_TYPES = Set.of(
            "run.completed", "run.waiting_user", "run.failed", "run.cancelled");

    static RunDuration between(Instant startedAt, Instant endedAt) {
        if (startedAt == null || endedAt == null) {
            return new RunDuration(null);
        }
        try {
            return new RunDuration(Math.max(0L, Duration.between(startedAt, endedAt).toMillis()));
        } catch (ArithmeticException ex) {
            return new RunDuration(null);
        }
    }

    static RunDuration forRun(ChatRun run, Instant sampledAt) {
        // 外部收口若读取到已结束的执行阶段，不能把等待用户的时间重新计入。
        return run == null ? new RunDuration(null)
                : between(run.startedAt(), run.finishedAt() == null ? sampledAt : run.finishedAt());
    }

    ChatEvent decorate(ChatEvent event) {
        if (event == null || millis == null || event.type() == null || !TERMINAL_TYPES.contains(event.type())) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload() == null ? Map.of() : event.payload());
        payload.put(KEY, millis);
        Map<String, Object> frozen = java.util.Collections.unmodifiableMap(payload);
        return switch (event.type()) {
            case "run.completed" -> new RunCompletedEvent(event.runId(), event.sessionId(), event.sequence(),
                    event.createdAt(), frozen);
            case "run.waiting_user" -> new RunWaitingUserEvent(event.runId(), event.sessionId(), event.sequence(),
                    event.createdAt(), frozen);
            case "run.cancelled" -> new RunCancelledEvent(event.runId(), event.sessionId(), event.sequence(),
                    event.createdAt(), frozen);
            case "run.failed" -> new ErrorEvent(event.runId(), event.sessionId(), event.sequence(),
                    event.createdAt(), event instanceof ErrorEvent error ? error.code() : text(payload.get("code")),
                    event instanceof ErrorEvent error ? error.message() : text(payload.get("message")), frozen);
            default -> event;
        };
    }

    String mergeMetadata(ObjectMapper mapper, String metadataJson) {
        try {
            JsonNode parsed = metadataJson == null || metadataJson.isBlank()
                    ? mapper.createObjectNode() : mapper.readTree(metadataJson);
            if (!(parsed instanceof ObjectNode metadata)) {
                return metadataJson;
            }
            if (millis == null) {
                if (!metadata.has(KEY)) {
                    return metadataJson;
                }
                metadata.remove(KEY);
            } else {
                metadata.put(KEY, millis);
            }
            return mapper.writeValueAsString(metadata);
        } catch (JsonProcessingException | RuntimeException ex) {
            // 展示指标不能因存量 metadata 异常破坏终态，保留原值和原有错误处理边界。
            return metadataJson;
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
