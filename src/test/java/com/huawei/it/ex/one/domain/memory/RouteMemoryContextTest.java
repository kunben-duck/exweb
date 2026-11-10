/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class RouteMemoryContextTest {
    @Test
    void optionalIdDoesNotChangeDeduplicationOrOrdering() {
        Map<String, Object> old = Map.of("type", "route", "query", "question", "intent", "Knowledge");
        Map<String, Object> enriched = new LinkedHashMap<>(old);
        enriched.put("intentId", "original-id");
        Map<String, Object> clarification = Map.of("type", "clarify", "query", "which period?");
        List<Map<String, Object>> history = new ArrayList<>(List.of(old, clarification));
        RouteMemoryContext.appendHistory(history, enriched);
        RouteMemoryContext.appendHistory(history, old);
        RouteMemoryContext.appendHistory(history, enriched);
        assertThat(history).containsExactly(enriched, clarification);
        assertThat(old).doesNotContainKey("intentId");
    }
}
