package com.huawei.finance.front.one.domain.usecase;

import java.util.Map;

/**
 * 用例库匹配结果。
 *
 * <p>用例库是“已沉淀业务样例”的优先路由来源。它不直接执行任务，只告诉主控服务是否命中、
 * 命中分数以及应该调用哪个 SubAgent。</p>
 */
public record UseCaseMatchResult(
        boolean matched,
        double score,
        String subAgentCode,
        String reason,
        Map<String, Object> slots,
        Map<String, Object> raw
) {
    public UseCaseMatchResult {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    public static UseCaseMatchResult notMatched(String reason) {
        return new UseCaseMatchResult(false, 0.0, null, reason, Map.of(), Map.of());
    }

    public boolean accepted(double threshold) {
        // 命中、分数达标、SubAgent 明确三者同时满足，才允许跳过意图服务进入 SubAgent。
        return matched && score >= threshold && subAgentCode != null && !subAgentCode.isBlank();
    }
}
