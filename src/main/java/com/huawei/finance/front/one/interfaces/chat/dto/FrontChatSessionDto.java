package com.huawei.finance.front.one.interfaces.chat.dto;

import java.time.Instant;

/**
 * 前端聊天会话 DTO。
 *
 * @param sessionId 前端聊天会话标识。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param title 会话标题。
 * @param status 会话状态。
 * @param channel 会话来源渠道。
 * @param createdAt 创建时间。
 * @param updatedAt 最近更新时间。
 */
public record FrontChatSessionDto(
        String sessionId,
        String tenantId,
        String userId,
        String title,
        String status,
        String channel,
        Instant createdAt,
        Instant updatedAt
) {}
