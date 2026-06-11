package com.huawei.finance.front.one.application.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文档 provider 配置。
 *
 * <p>前端始终调用统一文档库接口，后端根据 targetProvider 选择不同 provider adapter。
 * 该配置只描述 provider 能力、接口地址和响应字段映射，避免把老 Agent 或领域 Agent 的上传协议
 * 写死到文档应用服务。</p>
 */
@ConfigurationProperties(prefix = "financeex.documents.providers")
public class DocumentProviderProperties {
    /** 默认文档 provider；前端不传 targetProvider 时使用。 */
    private String defaultProvider = "default-storage";
    /** provider 编码到配置的映射。 */
    private Map<String, ProviderEntry> entries = defaultEntries();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Map<String, ProviderEntry> getEntries() {
        return entries;
    }

    public void setEntries(Map<String, ProviderEntry> entries) {
        this.entries = entries == null || entries.isEmpty() ? defaultEntries() : new LinkedHashMap<>(entries);
    }

    public ProviderEntry entry(String providerCode) {
        String code = providerCode == null || providerCode.isBlank() ? defaultProvider : providerCode.trim();
        return entries.get(code);
    }

    public String normalizedDefaultProvider() {
        return defaultProvider == null || defaultProvider.isBlank() ? "default-storage" : defaultProvider.trim();
    }

    private static Map<String, ProviderEntry> defaultEntries() {
        Map<String, ProviderEntry> defaults = new LinkedHashMap<>();
        ProviderEntry defaultStorage = new ProviderEntry();
        defaultStorage.setType("object-storage");
        defaultStorage.setEnabled(true);
        defaultStorage.setSource("LOCAL_UPLOAD");
        defaults.put("default-storage", defaultStorage);
        return defaults;
    }

    /**
     * 单个文档 provider 的配置。
     */
    public static class ProviderEntry {
        /** provider 类型：object-storage 或 http。 */
        private String type = "object-storage";
        /** provider 是否启用。 */
        private boolean enabled = true;
        /** 保存到 UploadedDocument.source 的来源编码。 */
        private String source = "LOCAL_UPLOAD";
        /** HTTP provider 的基础地址。 */
        private String baseUrl = "";
        /** HTTP provider 调用超时时间。 */
        private Duration timeout = Duration.ofSeconds(30);
        /** 是否允许把上传入口 Cookie 作为下游 provider upload 请求头透传。 */
        private boolean forwardCookie = false;
        /** 上传接口配置。 */
        private Endpoint upload = new Endpoint();
        /** 下载接口配置。 */
        private Endpoint download = new Endpoint();
        /** 状态接口配置。 */
        private Endpoint status = new Endpoint();
        /** 上传响应字段映射。 */
        private ResponseMapping responseMapping = new ResponseMapping();

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public boolean isForwardCookie() { return forwardCookie; }
        public void setForwardCookie(boolean forwardCookie) { this.forwardCookie = forwardCookie; }
        public Endpoint getUpload() { return upload; }
        public void setUpload(Endpoint upload) { this.upload = upload == null ? new Endpoint() : upload; }
        public Endpoint getDownload() { return download; }
        public void setDownload(Endpoint download) { this.download = download == null ? new Endpoint() : download; }
        public Endpoint getStatus() { return status; }
        public void setStatus(Endpoint status) { this.status = status == null ? new Endpoint() : status; }
        public ResponseMapping getResponseMapping() { return responseMapping; }
        public void setResponseMapping(ResponseMapping responseMapping) {
            this.responseMapping = responseMapping == null ? new ResponseMapping() : responseMapping;
        }
    }

    /**
     * provider HTTP endpoint 配置。
     */
    public static class Endpoint {
        /** endpoint 是否启用。 */
        private boolean enabled = false;
        /** endpoint path，可包含 {providerDocumentId} 占位符。 */
        private String path = "";
        /** HTTP method，当前上传默认 POST，下载/状态默认 GET。 */
        private String method = "POST";
        /** 上传内容类型，首版支持 multipart。 */
        private String contentType = "multipart";
        /** multipart 文件字段名。 */
        private String fileField = "file";
        /** 额外 form 字段模板，例如 skillId: "${skillId}"。 */
        private Map<String, String> extraFormFields = new LinkedHashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        public String getFileField() { return fileField; }
        public void setFileField(String fileField) { this.fileField = fileField; }
        public Map<String, String> getExtraFormFields() { return extraFormFields; }
        public void setExtraFormFields(Map<String, String> extraFormFields) {
            this.extraFormFields = extraFormFields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extraFormFields);
        }
    }

    /**
     * provider 上传响应字段映射配置。
     */
    public static class ResponseMapping {
        /** 响应中文档数组路径，例如 data 或 result.files。 */
        private String dataArrayPath = "data";
        /** provider 文档 ID 字段名。 */
        private String documentIdField = "docid";
        /** provider 文档名字段名。 */
        private String documentNameField = "docname";
        /** provider 文档大小字段名。 */
        private String documentSizeField = "docsize";
        /** provider 文档 URL 字段名；当响应没有文档 ID 但有 URL 时，仍视为上传成功。 */
        private String documentUrlField = "url";
        /** 允许保存到 metadataJson.providerDocument 的 provider 私有字段。 */
        private List<String> metadataFields = new ArrayList<>(List.of("levelCode", "serverName", "version"));

        public String getDataArrayPath() { return dataArrayPath; }
        public void setDataArrayPath(String dataArrayPath) { this.dataArrayPath = dataArrayPath; }
        public String getDocumentIdField() { return documentIdField; }
        public void setDocumentIdField(String documentIdField) { this.documentIdField = documentIdField; }
        public String getDocumentNameField() { return documentNameField; }
        public void setDocumentNameField(String documentNameField) { this.documentNameField = documentNameField; }
        public String getDocumentSizeField() { return documentSizeField; }
        public void setDocumentSizeField(String documentSizeField) { this.documentSizeField = documentSizeField; }
        public String getDocumentUrlField() { return documentUrlField; }
        public void setDocumentUrlField(String documentUrlField) { this.documentUrlField = documentUrlField; }
        public List<String> getMetadataFields() { return metadataFields; }
        public void setMetadataFields(List<String> metadataFields) {
            this.metadataFields = metadataFields == null ? List.of() : List.copyOf(metadataFields);
        }
    }
}
