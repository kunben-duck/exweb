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
 * @param parentMessageId 消息树父节点。
 * @param nodeOrder 会话内消息节点创建序号。
 * @param treeDepth 消息树深度。
 * @param siblingIndex 同父节点下同角色候选序号。
 * @param role 消息角色，例如 user、assistant。
 * @param content 完整消息正文。
 * @param tokenCount 消息 token 数估算值，可为空。
 * @param runId 产生该消息的 runId。
 * @param originType 消息来源类型，例如 NORMAL 或 BRANCH_SNAPSHOT。
 * @param locked 是否只读。
 * @param sourceSessionId 分支快照来源会话。
 * @param sourceMessageId 分支快照来源消息。
 * @param editedFromMessageId 编辑历史 user 消息的来源。
 * @param regeneratedFromMessageId 重新生成 assistant 消息的来源。
 * @param createdAt 消息创建时间。
 */
public record ChatMessageDto(
        String messageId,
        String sessionId,
        String parentMessageId,
        Long nodeOrder,
        Integer treeDepth,
        Integer siblingIndex,
        String role,
        String content,
        Integer tokenCount,
        String runId,
        String originType,
        boolean locked,
        String sourceSessionId,
        String sourceMessageId,
        String editedFromMessageId,
        String regeneratedFromMessageId,
        Instant createdAt
) {}
