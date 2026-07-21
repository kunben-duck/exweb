package com.huawei.it.ex.one.intent.compat.caselibrary.model;

import java.util.Map;

/**
 * 用例库匹配结果。
 *
 * <p>用例库是“已沉淀业务样例”的优先路由来源。它不直接执行任务，只告诉主控服务是否命中、
 * 命中分数以及应该调用哪个 DomainAgent。</p>
 *
 * @param matched true 表示用例库命中了已有业务样例。
 * @param score 命中分数，范围 0 到 1。
 * @param domainAgentId 命中后推荐调用的 DomainAgent ID。
 * @param reason 用例库返回的命中或未命中原因。
 * @param slots 用例库从样例中提取出的槽位。
 * @param raw 用例库原始响应或诊断信息。
 */
public record UseCaseMatchResult(
        boolean matched,
        double score,
        String domainAgentId,
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
        // 命中、分数达标、DomainAgent 明确三者同时满足，才允许跳过意图服务进入 DomainAgent。
        return matched && score >= threshold && domainAgentId != null && !domainAgentId.isBlank();
    }
}
