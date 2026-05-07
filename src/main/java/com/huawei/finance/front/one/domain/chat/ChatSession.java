package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;

/**
 * 前端聊天会话。
 *
 * @param id 会话唯一标识。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param title 会话标题。
 * @param status 会话状态，例如 ACTIVE、ARCHIVED。
 * @param channel 会话来源渠道，例如 web、im、mobile。
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
        Instant createdAt,
        Instant updatedAt
) {}
