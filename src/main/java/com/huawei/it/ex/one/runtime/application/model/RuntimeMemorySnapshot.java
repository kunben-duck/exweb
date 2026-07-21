package com.huawei.it.ex.one.runtime.application.model;

import java.util.List;

/** Optional memory context passed through the Runtime boundary. */
public record RuntimeMemorySnapshot(
        List<RuntimeMessageSnapshot> recentMessages,
        List<RuntimeLongTermMemorySnapshot> longTermMemories,
        RuntimeRouteMemorySnapshot routeMemory
) {
    public RuntimeMemorySnapshot {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        longTermMemories = longTermMemories == null ? List.of() : List.copyOf(longTermMemories);
        routeMemory = routeMemory == null ? RuntimeRouteMemorySnapshot.empty() : routeMemory;
    }

    public static RuntimeMemorySnapshot empty() {
        return new RuntimeMemorySnapshot(List.of(), List.of(), RuntimeRouteMemorySnapshot.empty());
    }
}
