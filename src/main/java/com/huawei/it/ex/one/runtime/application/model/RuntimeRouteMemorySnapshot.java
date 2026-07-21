package com.huawei.it.ex.one.runtime.application.model;

import java.util.List;
import java.util.Map;

/** Route-memory snapshot visible to a Runtime. */
public record RuntimeRouteMemorySnapshot(
        String routeTrigger,
        List<Map<String, Object>> history,
        Map<String, Object> lastIntentRejectReason
) {
    public RuntimeRouteMemorySnapshot {
        routeTrigger = routeTrigger == null || routeTrigger.isBlank() ? "first_turn" : routeTrigger;
        history = history == null ? List.of() : List.copyOf(history);
        lastIntentRejectReason = lastIntentRejectReason == null ? Map.of() : Map.copyOf(lastIntentRejectReason);
    }

    public static RuntimeRouteMemorySnapshot empty() {
        return new RuntimeRouteMemorySnapshot("first_turn", List.of(), Map.of());
    }
}
