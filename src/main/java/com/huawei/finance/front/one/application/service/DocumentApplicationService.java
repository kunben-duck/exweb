package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.command.DocumentUpdateCommand;
import com.huawei.finance.front.one.application.command.DocumentUploadCommand;
import com.huawei.finance.front.one.application.facade.DocumentFacade;
import com.huawei.finance.front.one.application.integration.document.DocumentRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.document.ObjectStorage;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.document.DocumentDownload;
import com.huawei.finance.front.one.domain.document.DocumentLibraryPage;
import com.huawei.finance.front.one.domain.document.DocumentLibraryQuery;
import com.huawei.finance.front.one.domain.document.DocumentSource;
import com.huawei.finance.front.one.domain.document.DocumentStatus;
import com.huawei.finance.front.one.domain.document.StoredObject;
import com.huawei.finance.front.one.domain.document.StoredObjectContent;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 文档库应用服务。
 *
 * <p>本服务是文档能力的主控入口：接口层先解析不可变 {@link UserContext}，这里只使用显式身份
 * 校验会话归属，通过 ObjectStorage 防腐层写入对象内容，再把文档资产元数据持久化到
 * DocumentRepository。</p>
 */
@Service
public class DocumentApplicationService implements DocumentFacade {
    private final ObjectStorage storage;
    private final DocumentRepository repository;
    private final SessionRepository sessionRepository;
    private final IdGenerator idGenerator;
    private final PermissionChecker permissionChecker;
    private final WorkloadConcurrencyLimiter concurrencyLimiter;

    public DocumentApplicationService(ObjectStorage storage, DocumentRepository repository, SessionRepository sessionRepository,
                                      IdGenerator idGenerator, PermissionChecker permissionChecker,
                                      WorkloadConcurrencyLimiter concurrencyLimiter) {
        this.storage = storage;
        this.repository = repository;
        this.sessionRepository = sessionRepository;
        this.idGenerator = idGenerator;
        this.permissionChecker = permissionChecker;
        this.concurrencyLimiter = concurrencyLimiter;
    }
    @Override
    public Mono<UploadedDocument> upload(UserContext user, DocumentUploadCommand command) {
        return Mono.fromCallable(() -> {
            permissionChecker.checkChatPermission(user);
            ensureOwnedSessionIfPresent(user, command.sessionId());

            // 先存对象，再保存数据库可检索的文档记录；对象 key 和文档行都使用同一份 UserContext。
            StoredObject object;
            try (WorkloadConcurrencyLimiter.Permit ignored = concurrencyLimiter.acquireDocumentStorage()) {
                object = putObjectClosingStream(user, command);
            }
            Instant now = Instant.now();
            String documentId = idGenerator.newId("doc",
                    IdGenerateContext.of(user.tenantId(), user.userId(), command.sessionId()));
            UploadedDocument doc = new UploadedDocument(
                    documentId,
                    user.tenantId(),
                    user.userId(),
                    blankToNull(command.sessionId()),
                    safeFilename(command.originalFilename()),
                    object.bucket(),
                    object.objectKey(),
                    object.contentType(),
                    object.sizeBytes(),
                    DocumentStatus.AVAILABLE.name(),
                    DocumentSource.LOCAL_UPLOAD.name(),
                    null,
                    metadataJson(storage.provider(), command.sessionId()),
                    now,
                    now
            );
            return repository.save(doc);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<DocumentLibraryPage> list(UserContext user, DocumentLibraryQuery query) {
        return Mono.fromCallable(() -> {
            permissionChecker.checkChatPermission(user);
            if (query != null) {
                ensureOwnedSessionIfPresent(user, query.sessionId());
            }
            return repository.listByOwner(user.tenantId(), user.userId(),
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
            StoredObjectContent content;
            try (WorkloadConcurrencyLimiter.Permit ignored = concurrencyLimiter.acquireDocumentStorage()) {
                content = storage.getObject(document.bucket(), document.objectKey());
            }
            return new DocumentDownload(document, content);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<StoredObjectContent> download(UserContext user, String documentId) {
        return prepareDownload(user, documentId).map(DocumentDownload::content);
    }

    @Override
    public List<AttachmentRef> resolveAttachmentsForUser(UserContext user, List<AttachmentRef> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        permissionChecker.checkChatPermission(user);
        return attachments.stream()
                .filter(Objects::nonNull)
                .map(attachment -> toTrustedAttachment(user, attachment))
                .toList();
    }

    private StoredObject putObjectClosingStream(UserContext user, DocumentUploadCommand command) {
        try (InputStream inputStream = command.inputStream()) {
            return storage.putObject(
                    user.tenantId(),
                    safeFilename(command.originalFilename()),
                    blankToNull(command.contentType()),
                    command.sizeBytes(),
                    inputStream
            );
        } catch (Exception ex) {
            throw new IllegalStateException("文档上传失败", ex);
        }
    }

    private AttachmentRef toTrustedAttachment(UserContext user, AttachmentRef attachment) {
        UploadedDocument document = loadOwnedAvailableDocument(user, attachment.documentId());
        // 前端传入的附件名称、MIME 和大小只用于展示草稿。进入 Runtime 前必须以文档库事实数据为准。
        return new AttachmentRef(
                document.id(),
                document.originalName(),
                document.contentType(),
                document.sizeBytes(),
                document.tokenSize(),
                document.source()
        );
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
        UploadedDocument document = repository.findByOwnerAndId(user.tenantId(), user.userId(), documentId)
                .orElseThrow(() -> new SecurityException("文档不存在或不属于当前用户"));
        return document;
    }

    private void ensureOwnedSessionIfPresent(UserContext user, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (sessionRepository.findByTenantIdAndUserIdAndId(user.tenantId(), user.userId(), sessionId).isEmpty()) {
            throw new SecurityException("文档不能绑定到不属于当前用户的会话");
        }
    }

    private String metadataJson(String provider, String sessionId) {
        // 当前不引入额外 JSON 依赖，保持元数据简单稳定；后续解析/索引服务可在该字段追加诊断信息。
        return "{\"storageProvider\":\"" + escape(provider) + "\",\"originSessionId\":\"" + escape(blankToNull(sessionId)) + "\"}";
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
