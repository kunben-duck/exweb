package com.huawei.it.ex.one.infrastructure.storage.api;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * api-store 文档上传配置。
 */
@Component
@ConfigurationProperties(prefix = "financeex.storage.api-store")
@ConditionalOnProperty(prefix = "financeex.storage", name = "provider", havingValue = "api-store")
public class ApiStoreStorageProperties {
    private String baseUrl = "";
    private String uploadPath = "/fina/agent/fileOperate/upload";
    private Duration timeout = Duration.ofSeconds(30);
    private boolean forwardCookie = false;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUploadPath() {
        return uploadPath;
    }

    public void setUploadPath(String uploadPath) {
        this.uploadPath = uploadPath;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public boolean isForwardCookie() {
        return forwardCookie;
    }

    public void setForwardCookie(boolean forwardCookie) {
        this.forwardCookie = forwardCookie;
    }

    Duration normalizedTimeout() {
        return timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(30) : timeout;
    }
}
