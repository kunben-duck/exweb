package com.huawei.finance.front.one.domain.intent;

import java.util.Map;

public record IntentDecision(
        String intentCode,
        String intentName,
        TaskComplexity complexity,
        double confidence,
        boolean simpleTask,
        Map<String, Object> slots,
        Map<String, Object> raw
) {}
