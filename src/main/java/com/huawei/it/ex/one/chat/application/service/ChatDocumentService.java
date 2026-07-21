package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.mapper.ChatDocumentAttachmentMapper;
import com.huawei.it.ex.one.chat.application.model.ResolvedChatAttachments;
import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
import com.huawei.it.ex.one.document.application.service.DocumentService;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Chat-side adapter for the document application boundary. */
@Service
public class ChatDocumentService {
    private final DocumentService documentService;

    public ChatDocumentService(DocumentService documentService) {
        this.documentService = documentService;
    }

    public ResolvedChatAttachments resolveChatAttachmentsForUser(
            UserContext user, List<AttachmentRef> attachments) {
        return ChatDocumentAttachmentMapper.toChatResolved(documentService.resolveChatAttachmentsForUser(
                user, ChatDocumentAttachmentMapper.toRequests(attachments)));
    }

    public List<UploadedDocument> resolveDocumentsForUser(UserContext user, List<AttachmentRef> attachments) {
        return documentService.resolveDocumentsForUser(
                user, ChatDocumentAttachmentMapper.toRequests(attachments));
    }

    public Map<String, Object> replaceRuntimeDocumentMetadata(
            Map<String, Object> metadata, List<UploadedDocument> documents) {
        return documentService.replaceRuntimeDocumentMetadata(metadata, documents);
    }
}
