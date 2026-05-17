package com.huawei.finance.front.one.interfaces.chat.dto;

import java.time.Instant;

/**
 * 前端历史消息 DTO。
 *
 * <p>该 DTO 用于会话切换后的历史消息回显。它只包含前端展示和恢复上下文需要的消息字段，
 * 不暴露租户和用户字段。</p>
 *
 * @param messageId 消息唯一标识。
 * @param sessionId 消息所属会话标识。
 * @param role 消息角色，例如 user、assistant。
 * @param content 完整消息正文。
 * @param tokenCount 消息 token 数估算值，可为空。
 * @param createdAt 消息创建时间。
 */
public record FrontChatMessageDto(
        String messageId,
        String sessionId,
        String role,
        String content,
        Integer tokenCount,
        Instant createdAt
) {}
