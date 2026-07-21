package com.huawei.it.ex.one.chat.application.model;

import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
import java.util.List;

/** Chat-owned view of a resolved document batch. */
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
