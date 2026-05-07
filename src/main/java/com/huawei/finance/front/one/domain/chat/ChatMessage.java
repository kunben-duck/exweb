package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;

/**
 * 聊天历史消息。
 *
 * @param id 消息唯一标识。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId 前端聊天会话标识。
 * @param role 消息角色，例如 user 或 assistant。
 * @param content 消息正文。
 * @param tokenCount 消息 token 数估算值，可为空。
 * @param createdAt 消息创建时间。
 */
public record ChatMessage(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String role,
        String content,
        Integer tokenCount,
        Instant createdAt
) {}
