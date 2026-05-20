package com.huawei.finance.front.one.interfaces.chat.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 前端聊天附件 DTO。
 *
 * @param documentId 文档上传后生成的文档标识。
 * @param name 附件展示名称。
 * @param contentType 附件 MIME 类型。
 * @param sizeBytes 附件大小，单位字节。
 * @param tokenSize 文档解析后的 token 数量，可为空。
 * @param source 文档来源，例如 LOCAL_UPLOAD、LIBRARY、CONNECTOR。
 */
public record ChatAttachmentDto(
        @Size(max = 64, message = "documentId 长度不能超过 64")
        String documentId,
        @Size(max = 255, message = "附件名称长度不能超过 255")
        String name,
        @Size(max = 128, message = "contentType 长度不能超过 128")
        String contentType,
        @PositiveOrZero(message = "sizeBytes 不能为负数")
        Long sizeBytes,
        @PositiveOrZero(message = "tokenSize 不能为负数")
        Long tokenSize,
        @Size(max = 64, message = "source 长度不能超过 64")
        String source
) {}
