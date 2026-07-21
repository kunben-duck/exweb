package com.huawei.it.ex.one.document.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.document.application.repository.DocumentRepository;
import com.huawei.it.ex.one.document.application.client.DocumentStorage;
import com.huawei.it.ex.one.document.application.model.DocumentAttachmentRequest;
import com.huawei.it.ex.one.document.application.model.DocumentStorageUploadRequest;
import com.huawei.it.ex.one.document.application.model.DocumentUpdateCommand;
import com.huawei.it.ex.one.document.application.model.DocumentUploadCommand;
import com.huawei.it.ex.one.document.application.model.ResolvedDocumentAttachment;
import com.huawei.it.ex.one.document.application.model.ResolvedDocumentAttachments;
import com.huawei.it.ex.one.common.id.IdGenerateContext;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.document.domain.DocumentDownload;
import com.huawei.it.ex.one.document.domain.DocumentLibraryPage;
import com.huawei.it.ex.one.document.domain.DocumentLibraryQuery;
import com.huawei.it.ex.one.document.domain.DocumentStatus;
import com.huawei.it.ex.one.document.domain.StoredObjectContent;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 文档库应用服务。
 *
 * <p>本服务是文档能力的主控入口：接口层先解析不可变 {@link UserContext}，这里只使用显式身份
 * 校验会话归属，根据后端 {@code financeex.storage.provider} 选择唯一文档存储实现，再把文档资产
 * 元数据持久化到 DocumentRepository。前端不再通过 targetProvider 选择上传目的地。</p>
 */
@Service
public class DocumentApplicationService implements DocumentService {
    private final DocumentRepository repository;
    private final ChatSessionOwnershipService sessionOwnershipService;
    private final IdGenerator idGenerator;
    private final PermissionChecker permissionChecker;
    private final DocumentStorage storage;
    private final DocumentRuntimeMetadataMapper runtimeMetadataMapper;

    public DocumentApplicationService(DocumentRepository repository,
                                      ChatSessionOwnershipService sessionOwnershipService,
                                      IdGenerator idGenerator, PermissionChecker permissionChecker,
                                      DocumentStorage storage, ObjectMapper objectMapper) {
        this.repository = repository;
        this.sessionOwnershipService = sessionOwnershipService;
        this.idGenerator = idGenerator;
        this.permissionChecker = permissionChecker;
        this.storage = storage;
        this.runtimeMetadataMapper = new DocumentRuntimeMetadataMapper(objectMapper);
    }
    @Override
    public Mono<UploadedDocument> upload(UserContext user, DocumentUploadCommand command) {
        return Mono.fromCallable(() -> {
            try {
                permissionChecker.checkChatPermission(user);
                ensureOwnedSessionIfPresent(user, command.sessionId());
                String documentId = idGenerator.newId("doc",
                        IdGenerateContext.of(user.tenantId(), user.ownerUserId(), command.sessionId()));
                UploadedDocument doc = storage.upload(new DocumentStorageUploadRequest(user, documentId, command));
                return repository.save(doc);
            } finally {
                closeQuietly(command.inputStream());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<DocumentLibraryPage> list(UserContext user, DocumentLibraryQuery query) {
        return Mono.fromCallable(() -> {
            permissionChecker.checkChatPermission(user);
            if (query != null) {
                ensureOwnedSessionIfPresent(user, query.sessionId());
            }
            return repository.listByOwner(user.tenantId(), user.ownerUserId(),
                    query == null ? new DocumentLibraryQuery(null, 20, null) : query);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<UploadedDocument> get(UserContext user, String documentId) {
        return Mono.fromCallable(() -> {
            permissionChecker.checkChatPermission(user);
            return loadOwnedDocument(user, documentId);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<UploadedDocument> update(UserContext user, String documentId, DocumentUpdateCommand command) {
        return Mono.fromCallable(() -> {
            permissionChecker.checkChatPermission(user);
            UploadedDocument current = loadOwnedDocument(user, documentId);
            String nextName = command == null || command.originalName() == null || command.originalName().isBlank()
                    ? current.originalName()
                    : safeFilename(command.originalName());
            String nextMetadata = command == null || command.metadataJson() == null || command.metadataJson().isBlank()
                    ? current.metadataJson()
                    : command.metadataJson().trim();
            UploadedDocument updated = new UploadedDocument(
                    current.id(),
                    current.tenantId(),
                    current.userId(),
                    current.sessionId(),
                    nextName,
                    current.bucket(),
                    current.objectKey(),
                    current.contentType(),
                    current.sizeBytes(),
                    current.status(),
                    current.source(),
                    current.tokenSize(),
                    nextMetadata,
                    current.createdAt(),
                    Instant.now()
            );
            return repository.save(updated);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<UploadedDocument> delete(UserContext user, String documentId) {
        return Mono.fromCallable(() -> {
            permissionChecker.checkChatPermission(user);
            UploadedDocument current = loadOwnedDocument(user, documentId);
            // 删除采用软删除：消息历史和审计仍可保留文档引用，但新聊天不能再使用该文档。
            UploadedDocument deleted = new UploadedDocument(
                    current.id(),
                    current.tenantId(),
                    current.userId(),
                    current.sessionId(),
                    current.originalName(),
                    current.bucket(),
                    current.objectKey(),
                    current.contentType(),
                    current.sizeBytes(),
                    DocumentStatus.DELETED.name(),
                    current.source(),
                    current.tokenSize(),
                    current.metadataJson(),
                    current.createdAt(),
                    Instant.now()
            );
            return repository.save(deleted);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<DocumentDownload> prepareDownload(UserContext user, String documentId) {
        return Mono.fromCallable(() -> {
            permissionChecker.checkChatPermission(user);
            UploadedDocument document = loadOwnedAvailableDocument(user, documentId);
            StoredObjectContent content = storage
                    .download(document)
                    .orElseThrow(() -> new IllegalStateException("DOCUMENT_CONTENT_MANAGED_BY_PROVIDER: 文档内容由下游 provider 管理"));
            return new DocumentDownload(document, content);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<StoredObjectContent> download(UserContext user, String documentId) {
        return prepareDownload(user, documentId).map(DocumentDownload::content);
    }

    @Override
    @Transactional(
            readOnly = true,
            timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}"
    )
    public List<ResolvedDocumentAttachment> resolveAttachmentsForUser(
            UserContext user, List<DocumentAttachmentRequest> attachments) {
        return resolveChatAttachmentsForUser(user, attachments).attachments();
    }

    @Override
    @Transactional(
            readOnly = true,
            timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}"
    )
    public List<UploadedDocument> resolveDocumentsForUser(
            UserContext user, List<DocumentAttachmentRequest> attachments) {
        return resolveChatAttachmentsForUser(user, attachments).documents();
    }

    @Override
    @Transactional(
            readOnly = true,
            timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}"
    )
    public ResolvedDocumentAttachments resolveChatAttachmentsForUser(
            UserContext user, List<DocumentAttachmentRequest> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return ResolvedDocumentAttachments.empty();
        }
        permissionChecker.checkChatPermission(user);
        Map<String, UploadedDocument> documentsById = new LinkedHashMap<>();
        List<ResolvedDocumentAttachment> trustedAttachments = new ArrayList<>();
        List<UploadedDocument> trustedDocuments = new ArrayList<>();
        for (DocumentAttachmentRequest attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            String documentId = normalizeDocumentId(attachment.documentId());
            UploadedDocument document = documentsById.computeIfAbsent(documentId,
                    ignored -> loadOwnedAvailableDocument(user, documentId));
            trustedAttachments.add(toTrustedAttachment(document));
            trustedDocuments.add(document);
        }
        return new ResolvedDocumentAttachments(trustedAttachments, trustedDocuments);
    }

    @Override
    public Map<String, Object> replaceRuntimeDocumentMetadata(Map<String, Object> metadata,
                                                               List<UploadedDocument> documents) {
        return runtimeMetadataMapper.replaceDocuments(metadata, documents);
    }

    @Override
    public Mono<UploadedDocument> prepareAccess(UserContext user, String documentId) {
        return Mono.fromCallable(() -> {
            permissionChecker.checkChatPermission(user);
            UploadedDocument document = loadOwnedAvailableDocument(user, documentId);
            if (!storage.downloadSupported(document)) {
                throw new IllegalStateException("DOCUMENT_CONTENT_MANAGED_BY_PROVIDER: 文档内容由下游 provider 管理");
            }
            return document;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private ResolvedDocumentAttachment toTrustedAttachment(UploadedDocument document) {
        // 前端传入的附件名称、MIME 和大小只用于展示草稿。进入 Runtime 前必须以文档库事实数据为准。
        return new ResolvedDocumentAttachment(
                document.id(),
                document.originalName(),
                document.contentType(),
                document.sizeBytes(),
                document.tokenSize(),
                document.source()
        );
    }

    private String normalizeDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("文档 ID 不能为空");
        }
        return documentId.trim();
    }

    private UploadedDocument loadOwnedAvailableDocument(UserContext user, String documentId) {
        UploadedDocument document = loadOwnedDocument(user, documentId);
        if (!document.availableForChat()) {
            throw new IllegalStateException("文档当前不可用于聊天: " + document.status());
        }
        return document;
    }

    private UploadedDocument loadOwnedDocument(UserContext user, String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("文档 ID 不能为空");
        }
        UploadedDocument document = repository.findByOwnerAndId(user.tenantId(), user.ownerUserId(), documentId)
                .orElseThrow(() -> new SecurityException("文档不存在或不属于当前用户"));
        return document;
    }

    private void ensureOwnedSessionIfPresent(UserContext user, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionOwnershipService.requireOwnedSession(user, sessionId);
    }

    private String safeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "document";
        }
        // 展示名也需要剥离客户端路径和控制字符，避免把本地路径或换行注入到文档库元数据。
        String filename = originalFilename.trim().replace('\\', '/');
        int lastSlash = filename.lastIndexOf('/');
        if (lastSlash >= 0) {
            filename = filename.substring(lastSlash + 1);
        }
        filename = filename.replaceAll("[\\r\\n\\t]", "_").trim();
        if (filename.isBlank() || ".".equals(filename) || "..".equals(filename)) {
            return "document";
        }
        return filename.length() > 255 ? filename.substring(filename.length() - 255) : filename;
    }

    private void closeQuietly(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (Exception ignored) {
            // provider adapter 已经正常关闭或异常路径关闭失败时，不让清理动作改变业务错误语义。
        }
    }
}
