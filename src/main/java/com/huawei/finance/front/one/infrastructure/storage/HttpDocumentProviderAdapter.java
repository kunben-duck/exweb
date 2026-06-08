package com.huawei.finance.front.one.infrastructure.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.DocumentProviderProperties;
import com.huawei.finance.front.one.application.integration.document.DocumentProviderAdapter;
import com.huawei.finance.front.one.application.integration.document.DocumentProviderUploadRequest;
import com.huawei.finance.front.one.application.service.WorkloadConcurrencyLimiter;
import com.huawei.finance.front.one.domain.document.DocumentStatus;
import com.huawei.finance.front.one.domain.document.StoredObjectContent;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 配置化 HTTP 文档 provider adapter。
 *
 * <p>该 adapter 用于老 Agent 和未来领域 Agent 的“先上传文件、再把 provider 文件 ID 传给 chat”
 * 场景。URL、multipart 文件字段、额外 form 字段和响应字段映射都来自配置，不写死具体服务协议。</p>
 */
@Component
public class HttpDocumentProviderAdapter implements DocumentProviderAdapter {
    private static final int MAX_METADATA_STRING_LENGTH = 2048;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final WorkloadConcurrencyLimiter concurrencyLimiter;

    public HttpDocumentProviderAdapter(WebClient.Builder webClientBuilder, ObjectMapper objectMapper,
                                       WorkloadConcurrencyLimiter concurrencyLimiter) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    @Override
    public boolean supportsType(String providerType) {
        return "http".equalsIgnoreCase(providerType);
    }

    @Override
    public UploadedDocument upload(DocumentProviderUploadRequest request) {
        DocumentProviderProperties.ProviderEntry provider = request.provider();
        DocumentProviderProperties.Endpoint upload = provider.getUpload();
        if (!upload.isEnabled()) {
            throw new IllegalArgumentException("文档 provider 未启用上传能力: " + request.providerCode());
        }
        /*
         * HTTP provider 上传需要把临时文件读入 multipart body。这里复用文档存储并发隔离，
         * 防止多个 legacy/领域 Agent provider 同时上传大文件时把 JVM 堆内存打满。
         */
        String response;
        try (WorkloadConcurrencyLimiter.Permit ignored = concurrencyLimiter.acquireDocumentStorage()) {
            byte[] content = readAllBytes(request);
            response = executeUpload(request, content);
        }
        ProviderDocument providerDocument = parseUploadResponse(request, response);
        Instant now = Instant.now();
        return new UploadedDocument(
                request.documentId(),
                request.user().tenantId(),
                request.user().userId(),
                blankToNull(request.command().sessionId()),
                safeFilename(blankToDefault(providerDocument.documentName(), request.command().originalFilename())),
                request.providerCode(),
                providerDocument.documentId(),
                request.command().contentType(),
                providerDocument.documentSize() == null ? request.command().sizeBytes() : providerDocument.documentSize(),
                DocumentStatus.AVAILABLE.name(),
                provider.getSource(),
                null,
                metadataJson(request, providerDocument),
                now,
                now
        );
    }

    @Override
    public Optional<StoredObjectContent> download(UploadedDocument document,
                                                  DocumentProviderProperties.ProviderEntry provider) {
        if (!downloadSupported(document, provider)) {
            return Optional.empty();
        }
        String url = fullUrl(provider, provider.getDownload().getPath())
                .replace("{providerDocumentId}", document.objectKey());
        byte[] bytes;
        try (WorkloadConcurrencyLimiter.Permit ignored = concurrencyLimiter.acquireDocumentStorage()) {
            bytes = webClientBuilder.build()
                    .method(HttpMethod.valueOf(blankToDefault(provider.getDownload().getMethod(), "GET").toUpperCase(Locale.ROOT)))
                    .uri(url)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(timeout(provider));
        }
        if (bytes == null) {
            bytes = new byte[0];
        }
        return Optional.of(new StoredObjectContent(document.bucket(), document.objectKey(), bytes.length,
                document.contentType(), new ByteArrayInputStream(bytes)));
    }

    @Override
    public boolean downloadSupported(UploadedDocument document, DocumentProviderProperties.ProviderEntry provider) {
        return provider.getDownload() != null
                && provider.getDownload().isEnabled()
                && provider.getDownload().getPath() != null
                && !provider.getDownload().getPath().isBlank();
    }

    private String executeUpload(DocumentProviderUploadRequest request, byte[] content) {
        DocumentProviderProperties.ProviderEntry provider = request.provider();
        DocumentProviderProperties.Endpoint upload = provider.getUpload();
        if (!"multipart".equalsIgnoreCase(upload.getContentType())) {
            throw new IllegalArgumentException("暂不支持的文档 provider 上传 content-type: " + upload.getContentType());
        }
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part(blankToDefault(upload.getFileField(), "file"), new NamedByteArrayResource(
                        content, safeFilename(request.command().originalFilename())))
                .filename(safeFilename(request.command().originalFilename()))
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        upload.getExtraFormFields().forEach((name, template) -> {
            String value = resolveTemplate(template, request);
            if (value != null) {
                builder.part(name, value);
            }
        });
        return webClientBuilder.build()
                .method(HttpMethod.valueOf(blankToDefault(upload.getMethod(), "POST").toUpperCase(Locale.ROOT)))
                .uri(fullUrl(provider, upload.getPath()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(String.class)
                .block(timeout(provider));
    }

    private ProviderDocument parseUploadResponse(DocumentProviderUploadRequest request, String response) {
        try {
            JsonNode root = objectMapper.readTree(response == null || response.isBlank() ? "{}" : response);
            JsonNode documentNode = selectFirstDocument(root, request.provider().getResponseMapping().getDataArrayPath());
            if (documentNode == null || !documentNode.isObject()) {
                throw new IllegalArgumentException("文档 provider 上传响应缺少文档数据: " + request.providerCode());
            }
            DocumentProviderProperties.ResponseMapping mapping = request.provider().getResponseMapping();
            String providerDocumentId = text(documentNode, mapping.getDocumentIdField());
            if (providerDocumentId == null || providerDocumentId.isBlank()) {
                throw new IllegalArgumentException("文档 provider 上传响应缺少文档 ID: " + request.providerCode());
            }
            String providerDocumentName = text(documentNode, mapping.getDocumentNameField());
            Long providerDocumentSize = longValue(documentNode, mapping.getDocumentSizeField());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("docId", providerDocumentId);
            metadata.put("docName", providerDocumentName);
            metadata.put("docSize", providerDocumentSize);
            for (String field : mapping.getMetadataFields()) {
                if (field == null || field.isBlank()) {
                    continue;
                }
                JsonNode value = documentNode.get(field);
                if (value != null && !value.isNull()) {
                    metadata.put(field, sanitizeValue(field, value));
                }
            }
            return new ProviderDocument(providerDocumentId, providerDocumentName, providerDocumentSize, metadata);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("文档 provider 上传响应解析失败: " + request.providerCode(), ex);
        }
    }

    private JsonNode selectFirstDocument(JsonNode root, String path) {
        JsonNode node = select(root, path);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            return node.isEmpty() ? null : node.get(0);
        }
        return node;
    }

    private JsonNode select(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return root;
        }
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            current = current == null ? null : current.get(segment);
            if (current == null || current.isMissingNode() || current.isNull()) {
                return null;
            }
        }
        return current;
    }

    private String metadataJson(DocumentProviderUploadRequest request, ProviderDocument providerDocument) {
        try {
            Map<String, Object> capabilities = new LinkedHashMap<>();
            capabilities.put("download", request.provider().getDownload().isEnabled());
            capabilities.put("status", request.provider().getStatus().isEnabled());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("providerCode", request.providerCode());
            metadata.put("targetProvider", request.providerCode());
            metadata.put("skillId", blankToNull(request.command().skillId()));
            metadata.put("providerDocument", providerDocument.metadata());
            metadata.put("capabilities", capabilities);
            if (request.command().metadataJson() != null && !request.command().metadataJson().isBlank()) {
                metadata.put("uploadMetadata", objectMapper.readTree(request.command().metadataJson()));
            }
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new IllegalStateException("文档 provider 元数据序列化失败: " + request.providerCode(), ex);
        }
    }

    private byte[] readAllBytes(DocumentProviderUploadRequest request) {
        try (var inputStream = request.command().inputStream()) {
            return inputStream.readAllBytes();
        } catch (Exception ex) {
            throw new IllegalStateException("读取 provider 上传文件失败", ex);
        }
    }

    private String resolveTemplate(String template, DocumentProviderUploadRequest request) {
        if (template == null) {
            return null;
        }
        return template
                .replace("${skillId}", blankToDefault(request.command().skillId(), ""))
                .replace("${sessionId}", blankToDefault(request.command().sessionId(), ""))
                .replace("${originalFilename}", blankToDefault(request.command().originalFilename(), ""));
    }

    private String fullUrl(DocumentProviderProperties.ProviderEntry provider, String path) {
        String baseUrl = provider.getBaseUrl() == null ? "" : provider.getBaseUrl().trim();
        if (baseUrl.isBlank()) {
            throw new IllegalArgumentException("文档 HTTP provider base-url 不能为空");
        }
        String nextPath = path == null ? "" : path.trim();
        if (nextPath.startsWith("http://") || nextPath.startsWith("https://")) {
            return nextPath;
        }
        if (baseUrl.endsWith("/") && nextPath.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + nextPath;
        }
        if (!baseUrl.endsWith("/") && !nextPath.startsWith("/")) {
            return baseUrl + "/" + nextPath;
        }
        return baseUrl + nextPath;
    }

    private Duration timeout(DocumentProviderProperties.ProviderEntry provider) {
        return provider.getTimeout() == null ? Duration.ofSeconds(30) : provider.getTimeout();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null || field == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node == null || field == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.longValue();
        }
        try {
            return Long.parseLong(value.asText(""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Object sanitizeValue(String field, JsonNode value) {
        if (field != null) {
            String normalized = field.toLowerCase(Locale.ROOT);
            if (normalized.contains("token") || normalized.contains("secret") || normalized.contains("password")
                    || normalized.contains("authorization") || normalized.contains("cookie")
                    || normalized.contains("apikey") || normalized.contains("api_key")) {
                return "[REDACTED]";
            }
        }
        if (value.isNumber()) {
            return value.numberValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        String text = value.asText("");
        return text.length() > MAX_METADATA_STRING_LENGTH ? text.substring(0, MAX_METADATA_STRING_LENGTH) : text;
    }

    private String safeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "document";
        }
        // provider 返回的文件名同样不可信，必须剥离路径和控制字符后再进入文档库展示字段。
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

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ProviderDocument(String documentId, String documentName, Long documentSize,
                                    Map<String, Object> metadata) {}

    private static class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
