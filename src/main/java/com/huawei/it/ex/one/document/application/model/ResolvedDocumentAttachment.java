package com.huawei.it.ex.one.document.application.model;

/**
 * 文档事实源返回给调用上下文的可信附件快照。
 */
public record ResolvedDocumentAttachment(
        String documentId,
        String name,
        String contentType,
        Long sizeBytes,
        Long tokenSize,
        String source
) {
}
