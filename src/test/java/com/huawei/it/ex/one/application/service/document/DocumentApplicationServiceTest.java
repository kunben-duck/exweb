package com.huawei.it.ex.one.application.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.application.command.DocumentUpdateCommand;
import com.huawei.it.ex.one.application.command.DocumentUploadCommand;
import com.huawei.it.ex.one.application.facade.ResolvedChatAttachments;
import com.huawei.it.ex.one.application.integration.document.DocumentStorage;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.document.DocumentRepository;
import com.huawei.it.ex.one.application.integration.document.ObjectStorage;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatSessionPage;
import com.huawei.it.ex.one.domain.document.DocumentDownload;
import com.huawei.it.ex.one.domain.document.DocumentLibraryPage;
import com.huawei.it.ex.one.domain.document.DocumentLibraryQuery;
import com.huawei.it.ex.one.domain.document.DocumentSource;
import com.huawei.it.ex.one.domain.document.DocumentStatus;
import com.huawei.it.ex.one.domain.document.StoredObject;
import com.huawei.it.ex.one.domain.document.StoredObjectContent;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.infrastructure.storage.object.ObjectStorageDocumentStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
class DocumentApplicationServiceTest {
    @Test
    void uploadStoresObjectAndCreatesAvailableLibraryDocument() {
        InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
        DocumentApplicationService service = service(repository);

        UploadedDocument document = service.upload(user(), new DocumentUploadCommand(
                "session1",
                "invoice.pdf",
                "application/pdf",
                3,
                new ByteArrayInputStream(new byte[] {1, 2, 3})
        )).block();

        assertThat(document).isNotNull();
        assertThat(document.id()).isEqualTo("doc_1");
        assertThat(document.tenantId()).isEqualTo("tenant1");
        assertThat(document.userId()).isEqualTo("user1");
        assertThat(document.status()).isEqualTo(DocumentStatus.AVAILABLE.name());
        assertThat(document.source()).isEqualTo(DocumentSource.LOCAL_UPLOAD.name());
        assertThat(document.bucket()).isEqualTo("bucket");
        assertThat(repository.saved).containsKey("doc_1");
    }

    @Test
    void resolveAttachmentsUsesTrustedDocumentMetadata() {
        InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
        UploadedDocument stored = document("doc1", DocumentStatus.AVAILABLE);
        repository.saved.put(stored.id(), stored);
        DocumentApplicationService service = service(repository);

        List<AttachmentRef> resolved = service.resolveAttachmentsForUser(user(), List.of(
                new AttachmentRef("doc1", "forged.txt", "text/plain", 1L)
        ));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).name()).isEqualTo("invoice.pdf");
        assertThat(resolved.get(0).contentType()).isEqualTo("application/pdf");
        assertThat(resolved.get(0).sizeBytes()).isEqualTo(128L);
        assertThat(resolved.get(0).source()).isEqualTo(DocumentSource.LOCAL_UPLOAD.name());
    }

    @Test
    void rejectUnavailableAttachment() {
        InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
        UploadedDocument stored = document("doc1", DocumentStatus.FAILED);
        repository.saved.put(stored.id(), stored);
        DocumentApplicationService service = service(repository);

        assertThatThrownBy(() -> service.resolveAttachmentsForUser(user(), List.of(
                new AttachmentRef("doc1", "invoice.pdf", "application/pdf", 128L)
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("文档当前不可用于聊天");
    }

    @Test
    void resolveChatAttachmentsLoadsEachUniqueDocumentOnceAndIgnoresForgedFields() {
        InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
        UploadedDocument stored = document("doc1", DocumentStatus.AVAILABLE);
        repository.saved.put(stored.id(), stored);
        DocumentApplicationService service = service(repository);

        ResolvedChatAttachments resolved = service.resolveChatAttachmentsForUser(user(), List.of(
                new AttachmentRef("doc1", "forged-a.txt", "text/plain", 1L),
                new AttachmentRef("doc1", "forged-b.txt", "text/plain", 2L)));

        assertThat(repository.findCalls).isEqualTo(1);
        assertThat(resolved.attachments()).extracting(AttachmentRef::name)
                .containsExactly("invoice.pdf", "invoice.pdf");
        assertThat(resolved.documents()).containsExactly(stored, stored);
    }

    @Test
    void replaceRuntimeDocumentMetadataPreservesSceneFieldsAndUsesTrustedReferences() {
        InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
        DocumentApplicationService service = service(repository);
        UploadedDocument byDocId = documentWithMetadata(
                "doc1", """
                        {"providerDocument":{"providerLocatorType":"DOC_ID","docId":"provider-1",
                        "url":"https://files.example/doc1","docName":"invoice.pdf","docSize":19800}}
                        """);
        UploadedDocument byUrl = documentWithMetadata(
                "doc2", """
                        {"providerDocument":{"providerLocatorType":"URL","url":"https://files.example/doc2",
                        "docName":"chart.png","serverName":"shenzhen"}}
                        """);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("language", "zh_CN");
        metadata.put("sceneParam", new HashMap<>(Map.of(
                "region", "CN",
                "docList", List.of(Map.of("docId", "forged")))));

        Map<String, Object> result = service.replaceRuntimeDocumentMetadata(
                metadata, List.of(byDocId, byUrl));

        assertThat(result).containsEntry("language", "zh_CN");
        Map<?, ?> sceneParam = (Map<?, ?>) result.get("sceneParam");
        assertThat(sceneParam.get("region")).isEqualTo("CN");
        assertThat(sceneParam.get("docList")).isEqualTo(List.of(
                Map.of(
                        "providerLocatorType", "DOC_ID",
                        "docId", "provider-1",
                        "url", "https://files.example/doc1",
                        "docName", "invoice.pdf",
                        "docSize", 19800),
                Map.of(
                        "providerLocatorType", "URL",
                        "url", "https://files.example/doc2",
                        "docName", "chart.png",
                        "serverName", "shenzhen")));
        assertThat(((Map<?, ?>) metadata.get("sceneParam")).get("docList"))
                .isEqualTo(List.of(Map.of("docId", "forged")));
    }

    @Test
    void replaceRuntimeDocumentMetadataRemovesUntrustedDocListWhenNoDocuments() {
        DocumentApplicationService service = service(new InMemoryDocumentRepository());

        Map<String, Object> result = service.replaceRuntimeDocumentMetadata(
                Map.of("sceneParam", Map.of("region", "CN", "docList", List.of(Map.of("docId", "forged")))),
                List.of());

        Map<?, ?> sceneParam = (Map<?, ?>) result.get("sceneParam");
        assertThat(sceneParam.get("region")).isEqualTo("CN");
        assertThat(sceneParam.containsKey("docList")).isFalse();
    }

    @Test
    void updateChangesDocumentDisplayMetadata() {
        InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
        UploadedDocument stored = document("doc1", DocumentStatus.AVAILABLE);
        repository.saved.put(stored.id(), stored);
        DocumentApplicationService service = service(repository);

        UploadedDocument updated = service.update(user(), "doc1", new DocumentUpdateCommand("new-name.pdf", "{\"tag\":\"finance\"}")).block();

        assertThat(updated).isNotNull();
        assertThat(updated.originalName()).isEqualTo("new-name.pdf");
        assertThat(updated.metadataJson()).contains("finance");
        assertThat(repository.saved.get("doc1").originalName()).isEqualTo("new-name.pdf");
    }

    @Test
    void uploadStoresSanitizedDisplayFilename() {
        InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
        DocumentApplicationService service = service(repository);

        UploadedDocument document = service.upload(user(), new DocumentUploadCommand(
                "session1",
                "C:\\users\\finance\\invoice\n2026.pdf",
                "application/pdf",
                1,
                new ByteArrayInputStream(new byte[] {1})
        )).block();

        assertThat(document).isNotNull();
        assertThat(document.originalName()).isEqualTo("invoice_2026.pdf");
    }

    @Test
    void managementGetCanReadFailedDocumentStatus() {
        InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
        UploadedDocument stored = document("doc1", DocumentStatus.FAILED);
        repository.saved.put(stored.id(), stored);
        DocumentApplicationService service = service(repository);

        UploadedDocument loaded = service.get(user(), "doc1").block();

        assertThat(loaded).isNotNull();
        assertThat(loaded.status()).isEqualTo(DocumentStatus.FAILED.name());
    }

    @Test
    void deleteMarksDocumentUnavailableWithoutRemovingMetadata() {
        InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
        UploadedDocument stored = document("doc1", DocumentStatus.FAILED);
        repository.saved.put(stored.id(), stored);
        DocumentApplicationService service = service(repository);

        UploadedDocument deleted = service.delete(user(), "doc1").block();

        assertThat(deleted).isNotNull();
        assertThat(deleted.status()).isEqualTo(DocumentStatus.DELETED.name());
        assertThat(repository.saved.get("doc1").status()).isEqualTo(DocumentStatus.DELETED.name());
    }

    @Test
    void downloadReadsObjectFromStorageAfterOwnershipCheck() {
        InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
        UploadedDocument stored = document("doc1", DocumentStatus.AVAILABLE);
        repository.saved.put(stored.id(), stored);
        DocumentApplicationService service = service(repository);

        DocumentDownload download = service.prepareDownload(user(), "doc1").block();

        assertThat(download).isNotNull();
        assertThat(download.document().id()).isEqualTo("doc1");
        assertThat(download.content().bucket()).isEqualTo("bucket");
        assertThat(download.content().sizeBytes()).isEqualTo(3L);
    }

    @Test
    void downloadRejectsUnavailableDocument() {
        InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
        UploadedDocument stored = document("doc1", DocumentStatus.PROCESSING);
        repository.saved.put(stored.id(), stored);
        DocumentApplicationService service = service(repository);

        assertThatThrownBy(() -> service.prepareDownload(user(), "doc1").block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("文档当前不可用于聊天");
    }

    private DocumentApplicationService service(InMemoryDocumentRepository repository) {
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        ObjectMapper objectMapper = new ObjectMapper();
        DocumentStorage storage = new ObjectStorageDocumentStorage(new RecordingObjectStorage(), limiter, objectMapper);
        return new DocumentApplicationService(
                repository,
                new FixedSessionRepository(),
                new FixedIdGenerator(),
                new PermissionChecker(),
                storage,
                objectMapper
        );
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private UploadedDocument document(String id, DocumentStatus status) {
        Instant now = Instant.now();
        return new UploadedDocument(
                id,
                "tenant1",
                "user1",
                "session1",
                "invoice.pdf",
                "bucket",
                "tenant1/invoice.pdf",
                "application/pdf",
                128L,
                status.name(),
                DocumentSource.LOCAL_UPLOAD.name(),
                32L,
                "{}",
                now,
                now
        );
    }

    private UploadedDocument documentWithMetadata(String id, String metadataJson) {
        UploadedDocument base = document(id, DocumentStatus.AVAILABLE);
        return new UploadedDocument(base.id(), base.tenantId(), base.userId(), base.sessionId(),
                base.originalName(), base.bucket(), base.objectKey(), base.contentType(), base.sizeBytes(),
                base.status(), base.source(), base.tokenSize(), metadataJson, base.createdAt(), base.updatedAt());
    }

    private static class RecordingObjectStorage implements ObjectStorage {
        @Override
        public StoredObject putObject(String tenantId, String originalFilename, String contentType, long sizeBytes, InputStream inputStream) {
            return new StoredObject("bucket", tenantId + "/" + originalFilename, sizeBytes, contentType);
        }

        @Override
        public StoredObjectContent getObject(String bucket, String objectKey) {
            return new StoredObjectContent(bucket, objectKey, 3L, "application/pdf",
                    new ByteArrayInputStream(new byte[] {1, 2, 3}));
        }

        @Override
        public String provider() {
            return "test";
        }
    }

    private static class InMemoryDocumentRepository implements DocumentRepository {
        private final Map<String, UploadedDocument> saved = new HashMap<>();
        private int findCalls;

        @Override
        public UploadedDocument save(UploadedDocument document) {
            saved.put(document.id(), document);
            return document;
        }

        @Override
        public Optional<UploadedDocument> findByOwnerAndId(String tenantId, String userId, String documentId) {
            findCalls++;
            return Optional.ofNullable(saved.get(documentId))
                    .filter(document -> tenantId.equals(document.tenantId()) && userId.equals(document.userId()));
        }

        @Override
        public DocumentLibraryPage listByOwner(String tenantId, String userId, DocumentLibraryQuery query) {
            return new DocumentLibraryPage(saved.values().stream().toList(), null);
        }
    }

    private static class FixedSessionRepository implements SessionRepository {
        @Override
        public Optional<ChatSession> findById(String sessionId) {
            return Optional.empty();
        }

        @Override
        public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            Instant now = Instant.now();
            return Optional.of(new ChatSession(sessionId, tenantId, userId, "title", "ACTIVE", "web", now, now));
        }

        @Override
        public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) {
            return List.of();
        }

        @Override
        public ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit) {
            return new ChatSessionPage(List.of(), null);
        }

        @Override
        public ChatSession save(ChatSession session) {
            return session;
        }
    }

    private static class FixedIdGenerator implements IdGenerator {
        @Override
        public String newId(String prefix, IdGenerateContext context) {
            return prefix + "_1";
        }
    }
}
