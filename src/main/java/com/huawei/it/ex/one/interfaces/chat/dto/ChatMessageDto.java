/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import java.time.Instant;
import java.util.List;

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
 * @param assistantSource assistant 消息来源，例如 relay 或 domain-agent；user 消息或无法识别时为空。
 * @param originType 消息来源类型，例如 NORMAL 或 BRANCH_SNAPSHOT。
 * @param locked 是否只读。
 * @param sourceSessionId 分支快照来源会话。
 * @param sourceMessageId 分支快照来源消息。
 * @param editedFromMessageId 编辑历史 user 消息的来源。
 * @param regeneratedFromMessageId 重新生成 assistant 消息的来源。
 * @param metadataJson 消息扩展元数据原始 JSON 字符串，可为空。
 * @param parts assistant 消息结构化过程信息；user 消息通常为空。
 * @param attachments 消息关联附件展示快照；通常用于 user 消息回显上传文档。
 * @param feedback 当前用户对该 assistant 消息的有效反馈；user 消息或已取消反馈时为空。
 * @param versionInfo 同父同角色候选版本摘要；没有可切换版本时为空。
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
        String assistantSource,
        String originType,
        boolean locked,
        String sourceSessionId,
        String sourceMessageId,
        String editedFromMessageId,
        String regeneratedFromMessageId,
        String metadataJson,
        List<ChatMessagePartDto> parts,
        List<ChatMessageAttachmentDto> attachments,
        MessageFeedbackDto feedback,
        ChatMessageVersionInfoDto versionInfo,
        Instant createdAt
) {}
