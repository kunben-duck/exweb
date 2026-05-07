package com.huawei.finance.front.one.domain.chat;

/**
 * 聊天消息中的附件引用。
 *
 * @param documentId 文档上传后生成的文档标识。
 * @param name 附件展示名称，通常为原始文件名。
 * @param contentType 附件 MIME 类型。
 * @param sizeBytes 附件大小，单位字节。
 */
public record AttachmentRef(
        String documentId,
        String name,
        String contentType,
        Long sizeBytes
) {}
