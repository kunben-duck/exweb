package com.huawei.it.ex.one.application.facade;

import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import java.util.List;

/**
 * 一次文档事实查询同时产生消息附件和 Runtime 文档元数据。
 */
public record ResolvedChatAttachments(
        List<AttachmentRef> attachments,
        List<UploadedDocument> documents
) {
    public ResolvedChatAttachments {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        documents = documents == null ? List.of() : List.copyOf(documents);
        if (attachments.size() != documents.size()) {
            throw new IllegalArgumentException("可信附件与文档元数据数量不一致");
        }
    }

    public static ResolvedChatAttachments empty() {
        return new ResolvedChatAttachments(List.of(), List.of());
    }
}
