package com.huawei.finance.front.one.interfaces.chat.dto;

/**
 * 前端聊天附件 DTO。
 *
 * @param documentId 文档上传后生成的文档标识。
 * @param name 附件展示名称。
 * @param contentType 附件 MIME 类型。
 * @param sizeBytes 附件大小，单位字节。
 */
public record FrontAttachmentDto(
        String documentId,
        String name,
        String contentType,
        Long sizeBytes
) {}
