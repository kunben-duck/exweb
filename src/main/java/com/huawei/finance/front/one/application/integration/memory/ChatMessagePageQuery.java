package com.huawei.finance.front.one.application.integration.memory;

/**
 * 历史消息分页查询条件。
 *
 * <p>leafMessageId 为空时查询当前 active path；非空时查询指定消息树 leaf 的路径。</p>
 */
public record ChatMessagePageQuery(
        String tenantId,
        String userId,
        String sessionId,
        String leafMessageId,
        String cursor,
        int limit
) {
}
