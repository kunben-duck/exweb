package com.huawei.it.ex.one.interfaces.chat.dto;

import java.time.Instant;
import java.util.List;

/**
 * 分享快照中的消息展示信息。
 *
 * @param messageId 来源消息 ID。
 * @param sessionId 来源会话 ID。
 * @param role 消息角色，首版分享只包含父 user 和目标 assistant。
 * @param content 消息正文。
 * @param runId assistant 消息来源 runId；user 消息可为空。
 * @param metadataJson 消息扩展元数据快照。
 * @param attachments 附件展示快照，不授予下载权限。
 * @param createdAt 来源消息创建时间。
 */
public record ChatShareSnapshotMessageDto(
        String messageId,
        String sessionId,
        String role,
        String content,
        String runId,
        String metadataJson,
        List<ChatShareAttachmentSnapshotDto> attachments,
        Instant createdAt
) {}
