package com.huawei.it.ex.one.application.integration.id;

import java.util.Map;

/**
 * ID 生成上下文。
 *
 * <p>第一版实现暂不使用这些字段；企业化实现可以按租户、用户、会话或扩展变量生成不同 ID。</p>
 *
 * @param tenantId 租户标识，用于生成具备租户隔离特征的 ID。
 * @param userId 用户标识，用于生成具备用户上下文特征的 ID。
 * @param sessionId 前端聊天会话标识。
 * @param runId 本轮执行追踪标识。
 * @param attributes 额外 ID 生成属性，供未来号段或企业 ID 服务适配使用。
 */
public record IdGenerateContext(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        Map<String, Object> attributes
) {
    /**
     * 创建空 ID 生成上下文。
     *
     * @return 空上下文。
     */
    public static IdGenerateContext empty() {
        return new IdGenerateContext(null, null, null, null, Map.of());
    }

    /**
     * 创建租户和用户级 ID 生成上下文。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @return ID 生成上下文。
     */
    public static IdGenerateContext of(String tenantId, String userId) {
        return new IdGenerateContext(tenantId, userId, null, null, Map.of());
    }

    /**
     * 创建会话级 ID 生成上下文。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return ID 生成上下文。
     */
    public static IdGenerateContext of(String tenantId, String userId, String sessionId) {
        return new IdGenerateContext(tenantId, userId, sessionId, null, Map.of());
    }

    /**
     * 创建 run 级 ID 生成上下文。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @param runId 本轮执行追踪标识。
     * @return ID 生成上下文。
     */
    public static IdGenerateContext of(String tenantId, String userId, String sessionId, String runId) {
        return new IdGenerateContext(tenantId, userId, sessionId, runId, Map.of());
    }
}
