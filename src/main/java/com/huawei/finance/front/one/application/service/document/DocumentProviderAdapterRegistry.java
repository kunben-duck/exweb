package com.huawei.finance.front.one.application.service.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.DocumentProviderProperties;
import com.huawei.finance.front.one.application.integration.document.DocumentProviderAdapter;
import com.huawei.finance.front.one.domain.document.DocumentSource;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * 文档 provider adapter 注册表。
 *
 * <p>应用服务只根据 targetProvider 或文档元数据解析 provider，不直接依赖具体 HTTP、S3/OBS
 * 或领域 Agent SDK。新增领域 Agent 文档源时，应增加配置和 adapter，而不是新增前端接口。</p>
 */
@Service
@EnableConfigurationProperties(DocumentProviderProperties.class)
public class DocumentProviderAdapterRegistry {
    private final List<DocumentProviderAdapter> adapters;
    private final DocumentProviderProperties properties;
    private final ObjectMapper objectMapper;

    public DocumentProviderAdapterRegistry(List<DocumentProviderAdapter> adapters,
                                           DocumentProviderProperties properties,
                                           ObjectMapper objectMapper) {
        this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ProviderResolution resolveForUpload(String targetProvider) {
        String providerCode = normalizeProviderCode(targetProvider);
        DocumentProviderProperties.ProviderEntry entry = entry(providerCode);
        if (!entry.isEnabled()) {
            throw new IllegalArgumentException("文档 provider 未启用: " + providerCode);
        }
        return new ProviderResolution(providerCode, entry, adapter(entry));
    }

    public ProviderResolution resolveForDocument(UploadedDocument document) {
        String providerCode = providerCodeFromMetadata(document);
        if (providerCode == null || providerCode.isBlank()) {
            if (DocumentSource.LOCAL_UPLOAD.name().equals(document.source())) {
                providerCode = properties.normalizedDefaultProvider();
            } else if (document.bucket() != null && properties.getEntries().containsKey(document.bucket())) {
                providerCode = document.bucket();
            }
        }
        providerCode = normalizeProviderCode(providerCode);
        DocumentProviderProperties.ProviderEntry entry = entry(providerCode);
        if (!entry.isEnabled()) {
            throw new IllegalStateException("文档 provider 已禁用: " + providerCode);
        }
        return new ProviderResolution(providerCode, entry, adapter(entry));
    }

    private String normalizeProviderCode(String targetProvider) {
        return targetProvider == null || targetProvider.isBlank()
                ? properties.normalizedDefaultProvider()
                : targetProvider.trim();
    }

    private DocumentProviderProperties.ProviderEntry entry(String providerCode) {
        DocumentProviderProperties.ProviderEntry entry = properties.entry(providerCode);
        if (entry == null) {
            throw new IllegalArgumentException("未知文档 provider: " + providerCode);
        }
        return entry;
    }

    private DocumentProviderAdapter adapter(DocumentProviderProperties.ProviderEntry entry) {
        String type = entry.getType() == null || entry.getType().isBlank() ? "object-storage" : entry.getType().trim();
        return adapters.stream()
                .filter(candidate -> candidate.supportsType(type))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到文档 provider adapter: " + type));
    }

    private String providerCodeFromMetadata(UploadedDocument document) {
        if (document == null || document.metadataJson() == null || document.metadataJson().isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(document.metadataJson());
            JsonNode providerCode = root.get("providerCode");
            return providerCode == null || providerCode.isNull() ? null : providerCode.asText(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * provider 解析结果。
     *
     * @param providerCode provider 编码。
     * @param provider provider 配置。
     * @param adapter provider adapter。
     */
    public record ProviderResolution(
            String providerCode,
            DocumentProviderProperties.ProviderEntry provider,
            DocumentProviderAdapter adapter
    ) {}
}
