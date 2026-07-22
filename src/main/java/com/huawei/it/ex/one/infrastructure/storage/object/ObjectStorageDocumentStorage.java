package com.huawei.it.ex.one.infrastructure.storage.object;

import com.huawei.it.ex.one.application.integration.document.DocumentStorage;
import com.huawei.it.ex.one.application.integration.document.DocumentStorageUploadRequest;
import com.huawei.it.ex.one.application.integration.document.ObjectStorage;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.domain.document.DocumentStatus;
import com.huawei.it.ex.one.domain.document.StoredObject;
import com.huawei.it.ex.one.domain.document.StoredObjectContent;
import com.huawei.it.ex.one.domain.document.UploadedDocument;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * local/huawei-s3 对象存储文档实现。
 *
 * <p>该实现承接原有对象存储上传能力。是否使用 local 或 huawei-s3 由底层
 * {@link ObjectStorage} bean 根据 {@code financeex.storage.provider} 决定。</p>
 */
@Component
@ConditionalOnExpression("'${financeex.storage.provider:}' == 'local' || '${financeex.storage.provider:}' == 'huawei-s3'")
public class ObjectStorageDocumentStorage implements DocumentStorage {
    private final ObjectStorage storage;
    private final WorkloadConcurrencyLimiter concurrencyLimiter;
    private final ObjectMapper objectMapper;

    public ObjectStorageDocumentStorage(ObjectStorage storage, WorkloadConcurrencyLimiter concurrencyLimiter,
                                        ObjectMapper objectMapper) {
        this.storage = storage;
        this.concurrencyLimiter = concurrencyLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public UploadedDocument upload(DocumentStorageUploadRequest request) {
        StoredObject object;
        try (WorkloadConcurrencyLimiter.Permit ignored = concurrencyLimiter.acquireDocumentStorage()) {
            object = putObjectClosingStream(request);
        }
        Instant now = Instant.now();
        return new UploadedDocument(
                request.documentId(),
                request.user().tenantId(),
                request.user().ownerUserId(),
                blankToNull(request.command().sessionId()),
                safeFilename(request.command().originalFilename()),
                object.bucket(),
                object.objectKey(),
                object.contentType(),
                object.sizeBytes(),
                DocumentStatus.AVAILABLE.name(),
                "LOCAL_UPLOAD",
                null,
                metadataJson(storage.provider(), request.command().sessionId(), request.command().metadataJson()),
                now,
                now
        );
    }

    @Override
    public Optional<StoredObjectContent> download(UploadedDocument document) {
        try (WorkloadConcurrencyLimiter.Permit ignored = concurrencyLimiter.acquireDocumentStorage()) {
            return Optional.of(storage.getObject(document.bucket(), document.objectKey()));
        }
    }

    @Override
    public boolean downloadSupported(UploadedDocument document) {
        return true;
    }

    private StoredObject putObjectClosingStream(DocumentStorageUploadRequest request) {
        try (InputStream inputStream = request.command().inputStream()) {
            return storage.putObject(
                    request.user().tenantId(),
                    safeFilename(request.command().originalFilename()),
                    blankToNull(request.command().contentType()),
                    request.command().sizeBytes(),
                    inputStream
            );
        } catch (Exception ex) {
            throw new IllegalStateException("文档上传失败", ex);
        }
    }

    private String metadataJson(String storageProvider, String sessionId, String uploadMetadata) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("storageProvider", storageProvider);
            metadata.put("sessionId", blankToNull(sessionId));
            metadata.put("capabilities", Map.of("download", true, "status", false));
            if (uploadMetadata != null && !uploadMetadata.isBlank()) {
                metadata.put("uploadMetadata", objectMapper.readTree(uploadMetadata));
            }
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new IllegalStateException("文档元数据序列化失败", ex);
        }
    }

    private String safeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "document";
        }
        // 展示名需要剥离客户端路径和控制字符，避免 Windows/macOS 本地路径或换行进入文档库元数据。
        String filename = name.trim().replace('\\', '/');
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
}
