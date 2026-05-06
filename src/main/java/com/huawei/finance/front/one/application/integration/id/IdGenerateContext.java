package com.huawei.finance.front.one.application.integration.id;

import java.util.Map;

/**
 * ID 生成上下文。
 *
 * <p>第一版实现暂不使用这些字段；企业化实现可以按租户、用户、会话或扩展变量生成不同 ID。</p>
 */
public record IdGenerateContext(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        Map<String, Object> attributes
) {
    public static IdGenerateContext empty() {
        return new IdGenerateContext(null, null, null, null, Map.of());
    }

    public static IdGenerateContext of(String tenantId, String userId) {
        return new IdGenerateContext(tenantId, userId, null, null, Map.of());
    }

    public static IdGenerateContext of(String tenantId, String userId, String sessionId) {
        return new IdGenerateContext(tenantId, userId, sessionId, null, Map.of());
    }

    public static IdGenerateContext of(String tenantId, String userId, String sessionId, String runId) {
        return new IdGenerateContext(tenantId, userId, sessionId, runId, Map.of());
    }
}
