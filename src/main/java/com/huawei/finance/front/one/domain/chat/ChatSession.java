package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;

/**
 * 前端聊天会话。
 *
 * @param id 会话唯一标识。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param title 会话标题。
 * @param status 会话状态，例如 ACTIVE、ARCHIVED、DELETED。
 * @param channel 会话来源渠道，例如 web、im、mobile。
 * @param currentLeafMessageId 当前会话激活路径的叶子消息。
 * @param rootSessionId 分支族根会话 ID，普通会话等于自身。
 * @param branchSourceSessionId 当前会话由哪个源会话分支而来。
 * @param branchSourceMessageId 当前会话从源会话哪条消息分支而来。
 * @param lastNodeOrder 当前会话内最大消息节点序号。
 * @param metadataJson 会话扩展元数据 JSON。
 * @param createdAt 创建时间。
 * @param updatedAt 最近更新时间。
 */
public record ChatSession(
        String id,
        String tenantId,
        String userId,
        String title,
        String status,
        String channel,
        String currentLeafMessageId,
        String rootSessionId,
        String branchSourceSessionId,
        String branchSourceMessageId,
        Long lastNodeOrder,
        String metadataJson,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * 兼容普通会话创建的便捷构造器；新代码应显式维护 current leaf 和分支字段。
     */
    public ChatSession(String id, String tenantId, String userId, String title, String status, String channel,
                       Instant createdAt, Instant updatedAt) {
        this(id, tenantId, userId, title, status, channel, null, id, null, null, 0L, null, createdAt, updatedAt);
    }

    public ChatSession {
        lastNodeOrder = lastNodeOrder == null ? 0L : lastNodeOrder;
        rootSessionId = rootSessionId == null || rootSessionId.isBlank() ? id : rootSessionId;
    }
}
