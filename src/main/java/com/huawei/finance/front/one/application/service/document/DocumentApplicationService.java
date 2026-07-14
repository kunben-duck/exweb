package com.huawei.finance.front.one.application.service.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.command.DocumentUpdateCommand;
import com.huawei.finance.front.one.application.command.DocumentUploadCommand;
import com.huawei.finance.front.one.application.facade.DocumentFacade;
import com.huawei.finance.front.one.application.facade.ResolvedChatAttachments;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.integration.document.DocumentRepository;
import com.huawei.finance.front.one.application.integration.document.DocumentStorage;
import com.huawei.finance.front.one.application.integration.document.DocumentStorageUploadRequest;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.document.DocumentDownload;
import com.huawei.finance.front.one.domain.document.DocumentLibraryPage;
import com.huawei.finance.front.one.domain.document.DocumentLibraryQuery;
import com.huawei.finance.front.one.domain.document.DocumentStatus;
import com.huawei.finance.front.one.domain.document.StoredObjectContent;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
public class DocumentApplicationService implements DocumentFacade {
    private final DocumentRepository repository;
    private final SessionRepository sessionRepository;
    private final IdGenerator idGenerator;
    private final PermissionChecker permissionChecker;
    private final DocumentStorage storage;
    private final ObjectMapper objectMapper;

    public DocumentApplicationService(DocumentRepository repository, SessionRepository sessionRepository,
                                      IdGenerator idGenerator, PermissionChecker permissionChecker,
                                      DocumentStorage storage, ObjectMapper objectMapper) {
        this.repository = repository;
        this.sessionRepository = sessionRepository;
        this.idGenerator = idGenerator;
        this.permissionChecker = permissionChecker;
        this.storage = storage;
        this.objectMapper = objectMapper;
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
    public List<AttachmentRef> resolveAttachmentsForUser(UserContext user, List<AttachmentRef> attachments) {
        return resolveChatAttachmentsForUser(user, attachments).attachments();
    }

    @Override
    @Transactional(
            readOnly = true,
            timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}"
    )
    public List<UploadedDocument> resolveDocumentsForUser(UserContext user, List<AttachmentRef> attachments) {
        return resolveChatAttachmentsForUser(user, attachments).documents();
    }

    @Override
    @Transactional(
            readOnly = true,
            timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}"
    )
    public ResolvedChatAttachments resolveChatAttachmentsForUser(UserContext user,
                                                                 List<AttachmentRef> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return ResolvedChatAttachments.empty();
        }
        permissionChecker.checkChatPermission(user);
        Map<String, UploadedDocument> documentsById = new LinkedHashMap<>();
        List<AttachmentRef> trustedAttachments = new ArrayList<>();
        List<UploadedDocument> trustedDocuments = new ArrayList<>();
        for (AttachmentRef attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            String documentId = normalizeDocumentId(attachment.documentId());
            UploadedDocument document = documentsById.computeIfAbsent(documentId,
                    ignored -> loadOwnedAvailableDocument(user, documentId));
            trustedAttachments.add(toTrustedAttachment(document));
            trustedDocuments.add(document);
        }
        return new ResolvedChatAttachments(trustedAttachments, trustedDocuments);
    }

    @Override
    public Map<String, Object> replaceRuntimeDocumentMetadata(Map<String, Object> metadata,
                                                               List<UploadedDocument> documents) {
        Map<String, Object> result = mutableDeepCopy(metadata);
        Object sceneValue = result.get("sceneParam");
        if (documents == null || documents.isEmpty()) {
            if (sceneValue instanceof Map<?, ?> sceneMap) {
                Map<String, Object> sceneCopy = mutableMap(sceneMap);
                sceneCopy.remove("docList");
                result.put("sceneParam", sceneCopy);
            }
            return immutableDeepCopy(result);
        }
        if (sceneValue != null && !(sceneValue instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("metadata.sceneParam 必须是 JSON object");
        }
        Map<String, Object> scene = sceneValue instanceof Map<?, ?> sceneMap
                ? mutableMap(sceneMap)
                : new LinkedHashMap<>();
        List<Map<String, Object>> docList = documents.stream()
                .filter(Objects::nonNull)
                .map(this::providerDocumentReference)
                .toList();
        scene.put("docList", docList);
        result.put("sceneParam", scene);
        return immutableDeepCopy(result);
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

    private AttachmentRef toTrustedAttachment(UploadedDocument document) {
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

    private String normalizeDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("文档 ID 不能为空");
        }
        return documentId.trim();
    }

    private Map<String, Object> providerDocumentReference(UploadedDocument document) {
        try {
            JsonNode root = objectMapper.readTree(document.metadataJson() == null ? "{}" : document.metadataJson());
            JsonNode providerDocument = root == null ? null : root.get("providerDocument");
            if (providerDocument == null || !providerDocument.isObject()) {
                throw new IllegalArgumentException("文档缺少 providerDocument 元数据: " + document.id());
            }
            String docId = textValue(providerDocument.get("docId"));
            if (docId != null) {
                return Map.of("docId", docId);
            }
            String url = textValue(providerDocument.get("url"));
            if (url != null) {
                return Map.of("url", url);
            }
            throw new IllegalArgumentException("文档 providerDocument 必须包含 docId 或 url: " + document.id());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("文档 providerDocument 元数据解析失败: " + document.id(), ex);
        }
    }

    private String textValue(JsonNode value) {
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private Map<String, Object> mutableDeepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key != null) {
                    copy.put(key, mutableDeepCopyValue(value));
                }
            });
        }
        return copy;
    }

    private Map<String, Object> mutableMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                copy.put(String.valueOf(key), mutableDeepCopyValue(value));
            }
        });
        return copy;
    }

    private Object mutableDeepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return mutableMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(mutableDeepCopyValue(item)));
            return copy;
        }
        return value;
    }

    private Map<String, Object> immutableDeepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableDeepCopyValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private Object immutableDeepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (key != null) {
                    copy.put(String.valueOf(key), immutableDeepCopyValue(nested));
                }
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(immutableDeepCopyValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
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
        if (sessionRepository.findByTenantIdAndUserIdAndId(user.tenantId(), user.ownerUserId(), sessionId).isEmpty()) {
            throw new SecurityException("文档不能绑定到不属于当前用户的会话");
        }
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
