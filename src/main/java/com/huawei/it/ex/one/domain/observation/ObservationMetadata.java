/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.observation;

import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 只从已校验归属的持久化记录恢复；客户端输入必须先剔除此命名空间。 */
public final class ObservationMetadata {
    public static final String KEY = "_turnObservation";
    private ObservationMetadata() { }

    public static Map<String, Object> remove(Map<String, Object> metadata) {
        if (metadata == null || !metadata.containsKey(KEY)) {
            return metadata == null ? Map.of() : metadata;
        }
        Map<String, Object> result = new LinkedHashMap<>(metadata);
        result.remove(KEY);
        return ChatPayloadMaps.immutableCopy(result);
    }

    public static Map<String, Object> with(Map<String, Object> metadata, TurnTraceContext context) {
        if (context == null) { return remove(metadata); }
        Map<String, Object> value = new LinkedHashMap<>();
        put(value, "traceId", context.traceId());
        put(value, "tenantId", context.tenantId());
        put(value, "userId", context.userId());
        put(value, "sessionId", context.sessionId());
        put(value, "rootRunId", context.rootRunId());
        put(value, "rootUserMessageId", context.rootUserMessageId());
        put(value, "sourceRunId", context.sourceRunId());
        put(value, "sourceTraceId", context.sourceTraceId());
        put(value, "originType", context.originType());
        put(value, "runId", context.runId());
        put(value, "userMessageId", context.userMessageId());
        put(value, "runOrdinal", context.runOrdinal());
        put(value, "startedAt", context.startedAt().toString());
        put(value, "firstBusinessFrameAt", context.firstBusinessFrameAt() == null
                ? null : context.firstBusinessFrameAt().toString());
        put(value, "firstBusinessFrameLatencyMs", context.firstBusinessFrameLatencyMs());
        put(value, "refusal", context.refusal());
        put(value, "parentObservationId", context.parentObservationId());
        put(value, "retainText", context.retainText());
        put(value, "clockInstanceId", context.clockInstanceId());
        put(value, "startedNanos", context.startedNanos());
        Map<String, Object> result = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        result.put(KEY, Map.copyOf(value));
        return ChatPayloadMaps.immutableCopy(result);
    }

    public static TurnTraceContext read(Map<String, Object> metadata, String tenant, String user) {
        try {
            if (metadata == null || !(metadata.get(KEY) instanceof Map<?, ?> value)
                    || !tenant.equals(value.get("tenantId")) || !user.equals(value.get("userId"))) { return null; }
            String id = text(value, "traceId");
            if (id == null || !id.matches("turn_[a-f0-9]{32}") || text(value, "rootRunId") == null
                    || text(value, "runId") == null) { return null; }
            String first = text(value, "firstBusinessFrameAt");
            Number ordinal = (Number) value.get("runOrdinal");
            Number latency = (Number) value.get("firstBusinessFrameLatencyMs");
            return new TurnTraceContext(id, tenant, user, text(value, "sessionId"), text(value, "rootRunId"),
                    text(value, "rootUserMessageId"), text(value, "sourceRunId"), text(value, "sourceTraceId"),
                    text(value, "originType"), text(value, "runId"), text(value, "userMessageId"),
                    ordinal.longValue(), Instant.parse(text(value, "startedAt")),
                    first == null ? null : Instant.parse(first), latency == null ? null : latency.longValue(),
                    Boolean.TRUE.equals(value.get("refusal")), text(value, "parentObservationId"),
                    !Boolean.FALSE.equals(value.get("retainText")), text(value, "clockInstanceId"),
                    value.get("startedNanos") instanceof Number number ? number.longValue() : null);
        } catch (RuntimeException ignored) { return null; }
    }

    private static String text(Map<?, ?> map, String key) {
        return map.get(key) instanceof String value && !value.isBlank() ? value : null;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) { map.put(key, value); }
    }
}
