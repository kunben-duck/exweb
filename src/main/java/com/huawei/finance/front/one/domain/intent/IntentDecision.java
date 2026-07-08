package com.huawei.finance.front.one.domain.intent;

import java.util.List;
import java.util.Map;

/**
 * 意图服务识别结果。
 *
 * <p>该模型只表达路由信号和已识别槽位，不直接触发执行。简单高置信任务可进入 DomainAgent，
 * 复杂或低置信任务交给 AgentRuntime。</p>
 *
 * @param intentCode 意图稳定编码。
 * @param intentName 意图展示名称。
 * @param complexity 任务复杂度分类。
 * @param confidence 意图识别置信度，范围 0 到 1。
 * @param simpleTask true 表示意图服务认为该请求是简单任务。
 * @param candidateDomainAgentId 简单任务推荐的 DomainAgent ID。
 * @param slots 已识别槽位。
 * @param missingSlots 仍缺失的槽位名称列表。
 * @param raw 意图服务原始响应或诊断信息。
 */
public record IntentDecision(
        String intentCode,
        String intentName,
        TaskComplexity complexity,
        double confidence,
        boolean simpleTask,
        String candidateDomainAgentId,
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
