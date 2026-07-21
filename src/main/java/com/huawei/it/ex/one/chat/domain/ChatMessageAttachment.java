package com.huawei.it.ex.one.chat.domain;

import java.time.Instant;

/**
 * 聊天消息到文档库资产的附件引用。
 *
 * @param id 附件引用唯一标识。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId 消息所属会话。
 * @param messageId 消息标识。
 * @param documentId 文档库资产标识。
 * @param attachmentOrder 同一消息内附件展示顺序。
 * @param name 附件展示名称快照。
 * @param contentType 附件 MIME 类型快照。
 * @param sizeBytes 附件大小快照。
 * @param sourceAttachmentId 分支快照复制时的来源附件引用 ID。
 * @param createdAt 创建时间。
 */
public record ChatMessageAttachment(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String messageId,
        String documentId,
        int attachmentOrder,
        String name,
        String contentType,
        Long sizeBytes,
        String sourceAttachmentId,
        Instant createdAt
) {}
