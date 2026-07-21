package com.huawei.it.ex.one.runtime.application.model;

import java.util.List;
import java.util.Map;

/** Immutable intent result consumed by Runtime adapters. */
public record RuntimeIntentSnapshot(
        String intentCode,
        String intentName,
        RuntimeTaskComplexity complexity,
        double confidence,
        boolean simpleTask,
        String candidateDomainAgentId,
        Map<String, Object> slots,
        List<String> missingSlots,
        Map<String, Object> raw
) {
    public RuntimeIntentSnapshot {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }
}
