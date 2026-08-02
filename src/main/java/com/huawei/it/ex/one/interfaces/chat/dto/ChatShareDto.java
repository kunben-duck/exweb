package com.huawei.it.ex.one.interfaces.chat.dto;

import java.time.Instant;

/**
 * 聊天消息分享元数据 DTO。
 *
 * @param shareId 分享 ID，前端可用它拼接分享页 URL。
 * @param title 分享标题。
 * @param scope 分享范围，SINGLE_TURN 或 SELECTED_MESSAGES。
 * @param visibility 访问模型，首版固定 INTERNAL。
 * @param status 分享状态，ACTIVE 或 REVOKED。
 * @param expiresAt 过期时间；为空表示不过期。
 * @param sourceSessionId 来源会话 ID。
 * @param sourceUserMessageId 首条来源 user 消息 ID；可能为空。
 * @param sourceAssistantMessageId 首条来源 assistant 消息 ID；可能为空。
 * @param sourceRunId 来源 runId。
 * @param createdAt 创建时间。
 * @param updatedAt 更新时间。
 */
public record ChatShareDto(
        String shareId,
        String title,
        String scope,
        String visibility,
        String status,
        Instant expiresAt,
        String sourceSessionId,
        String sourceUserMessageId,
        String sourceAssistantMessageId,
        String sourceRunId,
        Instant createdAt,
        Instant updatedAt
) {}
