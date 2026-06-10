package com.huawei.finance.front.one.infrastructure.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.document.DocumentProviderAdapter;
import com.huawei.finance.front.one.application.integration.document.DocumentProviderUploadRequest;
import com.huawei.finance.front.one.application.integration.document.ObjectStorage;
import com.huawei.finance.front.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.finance.front.one.domain.document.DocumentStatus;
import com.huawei.finance.front.one.domain.document.StoredObject;
import com.huawei.finance.front.one.domain.document.StoredObjectContent;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 默认对象存储文档 provider。
 *
 * <p>该 adapter 承接原有 S3/OBS/local object storage 上传能力。它是统一文档 provider 模型下的
 * default-storage 实现，保证前端不传 targetProvider 时行为保持不变。</p>
 */
@Component
public class ObjectStorageDocumentProviderAdapter implements DocumentProviderAdapter {
    private final ObjectStorage storage;
    private final WorkloadConcurrencyLimiter concurrencyLimiter;
    private final ObjectMapper objectMapper;

    public ObjectStorageDocumentProviderAdapter(ObjectStorage storage, WorkloadConcurrencyLimiter concurrencyLimiter,
                                                ObjectMapper objectMapper) {
        this.storage = storage;
        this.concurrencyLimiter = concurrencyLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supportsType(String providerType) {
        return "object-storage".equalsIgnoreCase(providerType);
    }

    @Override
    public UploadedDocument upload(DocumentProviderUploadRequest request) {
        StoredObject object;
        try (WorkloadConcurrencyLimiter.Permit ignored = concurrencyLimiter.acquireDocumentStorage()) {
            object = putObjectClosingStream(request);
        }
        Instant now = Instant.now();
        return new UploadedDocument(
                request.documentId(),
                request.user().tenantId(),
                request.user().userId(),
                blankToNull(request.command().sessionId()),
                safeFilename(request.command().originalFilename()),
                object.bucket(),
                object.objectKey(),
                object.contentType(),
                object.sizeBytes(),
                DocumentStatus.AVAILABLE.name(),
                request.provider().getSource(),
                null,
                metadataJson(request.providerCode(), request.command().sessionId(), request.command().metadataJson()),
                now,
                now
        );
    }

    @Override
    public Optional<StoredObjectContent> download(UploadedDocument document,
                                                  com.huawei.finance.front.one.application.config.DocumentProviderProperties.ProviderEntry provider) {
        try (WorkloadConcurrencyLimiter.Permit ignored = concurrencyLimiter.acquireDocumentStorage()) {
            return Optional.of(storage.getObject(document.bucket(), document.objectKey()));
        }
    }

    @Override
    public boolean downloadSupported(UploadedDocument document,
                                     com.huawei.finance.front.one.application.config.DocumentProviderProperties.ProviderEntry provider) {
        return true;
    }

    private StoredObject putObjectClosingStream(DocumentProviderUploadRequest request) {
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

    private String metadataJson(String providerCode, String sessionId, String uploadMetadata) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("providerCode", providerCode);
            metadata.put("targetProvider", providerCode);
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
