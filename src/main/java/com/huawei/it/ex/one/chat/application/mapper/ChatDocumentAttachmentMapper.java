package com.huawei.it.ex.one.chat.application.mapper;

import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.chat.application.model.ResolvedChatAttachments;
import com.huawei.it.ex.one.document.application.model.DocumentAttachmentRequest;
import com.huawei.it.ex.one.document.application.model.ResolvedDocumentAttachment;
import com.huawei.it.ex.one.document.application.model.ResolvedDocumentAttachments;
import java.util.ArrayList;
import java.util.List;

/** Performs the field-for-field mapping at the chat/document application boundary. */
public final class ChatDocumentAttachmentMapper {
    private ChatDocumentAttachmentMapper() {
    }

    public static List<DocumentAttachmentRequest> toRequests(List<AttachmentRef> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<DocumentAttachmentRequest> requests = new ArrayList<>(attachments.size());
        for (AttachmentRef attachment : attachments) {
            requests.add(attachment == null ? null : new DocumentAttachmentRequest(attachment.documentId()));
        }
        return java.util.Collections.unmodifiableList(requests);
    }

    public static List<AttachmentRef> toChatAttachments(List<ResolvedDocumentAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        return attachments.stream()
                .map(ChatDocumentAttachmentMapper::toChatAttachment)
                .toList();
    }

    public static AttachmentRef toChatAttachment(ResolvedDocumentAttachment attachment) {
        return new AttachmentRef(
                attachment.documentId(), attachment.name(), attachment.contentType(),
                attachment.sizeBytes(), attachment.tokenSize(), attachment.source());
    }

    public static ResolvedChatAttachments toChatResolved(ResolvedDocumentAttachments resolved) {
        if (resolved == null) {
            return ResolvedChatAttachments.empty();
        }
        return new ResolvedChatAttachments(toChatAttachments(resolved.attachments()), resolved.documents());
    }
}
