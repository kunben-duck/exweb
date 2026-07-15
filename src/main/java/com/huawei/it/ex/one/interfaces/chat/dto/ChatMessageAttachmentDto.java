package com.huawei.it.ex.one.interfaces.chat.dto;

import java.time.Instant;

/**
 * 历史消息附件展示 DTO。
 *
 * <p>该 DTO 只返回消息创建时保存的附件展示快照和 documentId，便于前端回显用户上传文档。
 * 文件下载、预览仍需走文档库接口重新鉴权。</p>
 *
 * @param attachmentId 消息附件引用 ID。
 * @param documentId 文档库资产 ID。
 * @param attachmentOrder 同一消息内附件展示顺序。
 * @param name 附件展示名称快照。
 * @param contentType 附件 MIME 类型快照。
 * @param sizeBytes 附件大小快照。
 * @param sourceAttachmentId 分支复制时的来源附件引用 ID。
 * @param createdAt 附件引用创建时间。
 */
public record ChatMessageAttachmentDto(
        String attachmentId,
        String documentId,
        int attachmentOrder,
        String name,
        String contentType,
        Long sizeBytes,
        String sourceAttachmentId,
        Instant createdAt
) {}
