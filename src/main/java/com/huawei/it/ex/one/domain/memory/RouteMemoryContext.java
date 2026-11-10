/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.memory;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发送给意图服务的路由上下文快照。
 */
public record RouteMemoryContext(
        String routeTrigger,
        List<Map<String, Object>> history,
        Map<String, Object> lastIntentRejectReason,
        @JsonIgnore String latestRouteSourceRunId
) {
    public RouteMemoryContext {
        routeTrigger = routeTrigger == null || routeTrigger.isBlank() ? "first_turn" : routeTrigger;
        history = history == null ? List.of() : List.copyOf(history);
        lastIntentRejectReason = lastIntentRejectReason == null ? Map.of() : Map.copyOf(lastIntentRejectReason);
    }

    public RouteMemoryContext(String routeTrigger, List<Map<String, Object>> history,
                              Map<String, Object> lastIntentRejectReason) {
        this(routeTrigger, history, lastIntentRejectReason, null);
    }

    public static RouteMemoryContext empty() {
        return new RouteMemoryContext("first_turn", List.of(), Map.of(), null);
    }

    public Map<String, Object> toConversationContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("routeTrigger", routeTrigger);
        context.put("lastIntentRejectReason", lastIntentRejectReason);
        context.put("history", history);
        return Map.copyOf(context);
    }

    public boolean hasHistory() {
        return !history.isEmpty();
    }

    /** The optional service ID enriches a history item, but does not change its existing deduplication key. */
    public static void appendHistory(List<Map<String, Object>> history, Map<String, Object> item) {
        Map<String, Object> key = new LinkedHashMap<>(item);
        key.remove("intentId");
        for (int index = 0; index < history.size(); index++) {
            Map<String, Object> existing = history.get(index);
            Map<String, Object> existingKey = new LinkedHashMap<>(existing);
            existingKey.remove("intentId");
            if (existingKey.equals(key)) {
                if (!existing.containsKey("intentId") && item.containsKey("intentId")) {
                    history.set(index, item);
                }
                return;
            }
        }
        history.add(item);
    }
}
