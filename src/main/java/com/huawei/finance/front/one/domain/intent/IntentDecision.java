package com.huawei.finance.front.one.domain.intent;

import java.util.List;
import java.util.Map;

public record IntentDecision(
        String intentCode,
        String intentName,
        TaskComplexity complexity,
        double confidence,
        boolean simpleTask,
        String candidateSubAgentCode,
        Map<String, Object> slots,
        List<String> missingSlots,
        Map<String, Object> raw
) {
    public IntentDecision(String intentCode, String intentName, TaskComplexity complexity, double confidence,
                          boolean simpleTask, Map<String, Object> slots, Map<String, Object> raw) {
        this(intentCode, intentName, complexity, confidence, simpleTask, null, slots, List.of(), raw);
    }

    public IntentDecision {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    public boolean highConfidence(double threshold) {
        return confidence >= threshold;
    }
}
