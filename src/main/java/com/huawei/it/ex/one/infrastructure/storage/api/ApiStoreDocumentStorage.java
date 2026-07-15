package com.huawei.it.ex.one.infrastructure.storage.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.application.command.DocumentUploadCommand;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.document.DocumentStorage;
import com.huawei.it.ex.one.application.integration.document.DocumentStorageUploadRequest;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.domain.document.DocumentSource;
import com.huawei.it.ex.one.domain.document.DocumentStatus;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * api-store 文档存储实现。
 *
 * <p>该实现对接固定的新文档上传接口。{@code metadata.skillId} 有值时透传为
 * multipart {@code skillId}，下游上传到企业 EDM；没有 skillId 时下游上传到 S3。</p>
 */
@Component
@ConditionalOnProperty(prefix = "financeex.storage", name = "provider", havingValue = "api-store")
public class ApiStoreDocumentStorage implements DocumentStorage {
    private static final List<String> PROVIDER_DOCUMENT_FIELDS = List.of(
            "docId", "docName", "docSize", "docStatus", "wmType", "serverName", "chunks",
            "docRelativePath", "fileSize", "checkCode", "failedDownloadChunks", "subAppId",
            "docVersion", "message", "error", "url", "taskId"
    );
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final WorkloadConcurrencyLimiter concurrencyLimiter;
    private final ApiStoreStorageProperties properties;

    public ApiStoreDocumentStorage(WebClient.Builder webClientBuilder, ObjectMapper objectMapper,
                                   WorkloadConcurrencyLimiter concurrencyLimiter,
                                   ApiStoreStorageProperties properties) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.concurrencyLimiter = concurrencyLimiter;
        this.properties = properties;
    }

    @Override
    public UploadedDocument upload(DocumentStorageUploadRequest request) {
        String response;
        byte[] content;
        try (WorkloadConcurrencyLimiter.Permit ignored = concurrencyLimiter.acquireDocumentStorage()) {
            content = readAllBytes(request.command());
            response = executeUpload(request, content);
        }
        ProviderDocument providerDocument = parseUploadResponse(request, response);
        Instant now = Instant.now();
        return new UploadedDocument(
                request.documentId(),
                request.user().tenantId(),
                request.user().ownerUserId(),
                blankToNull(request.command().sessionId()),
                safeFilename(blankToDefault(providerDocument.documentName(), request.command().originalFilename())),
                "api-store",
                providerDocument.objectKey(),
                request.command().contentType(),
                providerDocument.documentSize() == null ? request.command().sizeBytes() : providerDocument.documentSize(),
                DocumentStatus.AVAILABLE.name(),
                source(providerDocument),
                null,
                metadataJson(request, providerDocument),
                now,
                now
        );
    }

    private String executeUpload(DocumentStorageUploadRequest request, byte[] content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new NamedByteArrayResource(content, safeFilename(request.command().originalFilename())))
                .filename(safeFilename(request.command().originalFilename()))
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        String skillId = skillId(request.command());
        if (skillId != null) {
            builder.part("skillId", skillId);
        }
        return webClientBuilder.build()
                .post()
                .uri(fullUploadUrl())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(headers -> applyForwardedCookie(headers, request.command()))
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(String.class)
                .block(properties.normalizedTimeout());
    }

    private void applyForwardedCookie(HttpHeaders headers, DocumentUploadCommand command) {
        RuntimeForwardHeaders forwardHeaders = command.forwardHeaders();
        if (!properties.isForwardCookie() || forwardHeaders == null || !forwardHeaders.hasCookie()) {
            return;
        }
        /*
         * Cookie 只作为 api-store 出站请求头透传，不能进入 multipart form、metadataJson
         * 或前端响应，避免企业登录态落库。
         */
        headers.set(HttpHeaders.COOKIE, forwardHeaders.cookieHeader());
    }

    private ProviderDocument parseUploadResponse(DocumentStorageUploadRequest request, String response) {
        try {
            JsonNode root = objectMapper.readTree(response == null || response.isBlank() ? "{}" : response);
            if (!"success".equalsIgnoreCase(text(root, "status"))) {
                throw new IllegalArgumentException("api-store 上传失败: " + blankToDefault(text(root, "message"), response));
            }
            JsonNode data = root.get("data");
            JsonNode documentNode = data != null && data.isArray() && !data.isEmpty() ? data.get(0) : null;
            if (documentNode == null || !documentNode.isObject()) {
                throw new IllegalArgumentException("api-store 上传响应缺少 data[0]");
            }
            String docId = blankToNull(text(documentNode, "docId"));
            String url = blankToNull(text(documentNode, "url"));
            if (docId == null && url == null) {
                throw new IllegalArgumentException("api-store 上传响应缺少 docId 或 url");
            }
            String objectKey = docId != null ? docId : "api-store-url:" + sha256Hex(url);
            String docName = text(documentNode, "docName");
            Long docSize = longValue(documentNode, "docSize");
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("providerLocatorType", docId == null ? "URL" : "DOC_ID");
            for (String field : PROVIDER_DOCUMENT_FIELDS) {
                JsonNode value = documentNode.get(field);
                if (value != null && !value.isNull()) {
                    metadata.put(field, objectMapper.convertValue(value, Object.class));
                }
            }
            return new ProviderDocument(docId, url, objectKey, docName, docSize, metadata, skillId(request.command()));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("api-store 上传响应解析失败", ex);
        }
    }

    private String metadataJson(DocumentStorageUploadRequest request, ProviderDocument providerDocument) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("providerCode", "api-store");
            metadata.put("storageProvider", "api-store");
            metadata.put("sessionId", blankToNull(request.command().sessionId()));
            metadata.put("skillId", providerDocument.skillId());
            metadata.put("providerDocument", providerDocument.metadata());
            metadata.put("capabilities", Map.of("download", false, "status", false));
            if (request.command().metadataJson() != null && !request.command().metadataJson().isBlank()) {
                metadata.put("uploadMetadata", objectMapper.readTree(request.command().metadataJson()));
            }
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new IllegalStateException("api-store 文档元数据序列化失败", ex);
        }
    }

    private String source(ProviderDocument providerDocument) {
        return providerDocument.docId() != null && providerDocument.skillId() != null
                ? DocumentSource.DOMAIN_AGENT_UPLOAD.name()
                : DocumentSource.S3_UPLOAD.name();
    }

    private String skillId(DocumentUploadCommand command) {
        if (command.metadataJson() == null || command.metadataJson().isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(command.metadataJson());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("metadata 必须是合法 JSON 字符串", ex);
        }
        JsonNode skillId = root.get("skillId");
        if (skillId == null || skillId.isNull()) {
            return null;
        }
        if (!skillId.isTextual()) {
            throw new IllegalArgumentException("metadata.skillId 必须是字符串");
        }
        return skillId.asText();
    }

    private byte[] readAllBytes(DocumentUploadCommand command) {
        try (var inputStream = command.inputStream()) {
            return inputStream.readAllBytes();
        } catch (Exception ex) {
            throw new IllegalStateException("读取上传文件失败", ex);
        }
    }

    private String fullUploadUrl() {
        String baseUrl = requireText(properties.getBaseUrl(), "api-store base-url 不能为空");
        String path = blankToDefault(properties.getUploadPath(), "/fina/agent/fileOperate/upload");
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + path;
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }

    private String safeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "document";
        }
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

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.longValue();
        }
        String text = value.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (Exception ex) {
            throw new IllegalStateException("计算 api-store URL 定位符失败", ex);
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String blankToDefault(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private record ProviderDocument(String docId, String url, String objectKey, String documentName,
                                    Long documentSize, Map<String, Object> metadata, String skillId) {
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
