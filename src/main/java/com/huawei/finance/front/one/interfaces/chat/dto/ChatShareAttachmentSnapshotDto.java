package com.huawei.finance.front.one.interfaces.chat.dto;

/**
 * 分享快照中的附件展示信息。
 *
 * <p>该结构只用于展示附件名称和大小，不授予下载或预览权限。</p>
 *
 * @param documentId 文档库文档 ID。
 * @param name 附件展示名称。
 * @param contentType 附件 MIME 类型。
 * @param sizeBytes 附件大小。
 */
public record ChatShareAttachmentSnapshotDto(
        String documentId,
        String name,
        String contentType,
        Long sizeBytes
) {}
