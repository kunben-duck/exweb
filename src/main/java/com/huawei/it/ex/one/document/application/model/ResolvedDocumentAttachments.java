package com.huawei.it.ex.one.document.application.model;

import java.util.List;

/**
 * 一次文档事实查询同时产生消息附件和 Runtime 文档元数据。
 */
public record ResolvedDocumentAttachments(
        List<ResolvedDocumentAttachment> attachments,
        List<UploadedDocument> documents
) {
    public ResolvedDocumentAttachments {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        documents = documents == null ? List.of() : List.copyOf(documents);
        if (attachments.size() != documents.size()) {
            throw new IllegalArgumentException("可信附件与文档元数据数量不一致");
        }
    }

    public static ResolvedDocumentAttachments empty() {
        return new ResolvedDocumentAttachments(List.of(), List.of());
    }
}
