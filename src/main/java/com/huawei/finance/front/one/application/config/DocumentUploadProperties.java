package com.huawei.finance.front.one.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文档上传输入保护配置。
 *
 * <p>Servlet/WebFlux multipart 自身可以限制请求大小；这里在应用入口再次按实际临时文件大小校验，
 * 确保不同容器或网关配置下都不会把超大文件继续写入对象存储。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.document")
public class DocumentUploadProperties {
    /** 单个上传文档最大字节数。 */
    private long maxUploadSizeBytes = 50L * 1024L * 1024L;

    public long getMaxUploadSizeBytes() {
        return maxUploadSizeBytes;
    }

    public void setMaxUploadSizeBytes(long maxUploadSizeBytes) {
        this.maxUploadSizeBytes = maxUploadSizeBytes;
    }

    public long normalizedMaxUploadSizeBytes() {
        return Math.max(1L, maxUploadSizeBytes);
    }
}
