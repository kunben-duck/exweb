package com.huawei.finance.front.one.domain.chat;

/**
 * 聊天消息中的附件引用。
 *
 * @param documentId 文档上传后生成的文档标识。
 * @param name 附件展示名称，通常为原始文件名。
 * @param contentType 附件 MIME 类型。
 * @param sizeBytes 附件大小，单位字节。
 * @param tokenSize 文档解析后的 token 数量，可为空。
 * @param source 文档来源，例如 LOCAL_UPLOAD、LIBRARY、CONNECTOR。
 */
public record AttachmentRef(
        String documentId,
        String name,
        String contentType,
        Long sizeBytes,
        Long tokenSize,
        String source
) {
    /**
     * 只提供基础文件元数据的便捷构造器。进入应用层后会由文档库回查补齐 tokenSize/source。
     */
    public AttachmentRef(String documentId, String name, String contentType, Long sizeBytes) {
        this(documentId, name, contentType, sizeBytes, null, null);
    }
}
