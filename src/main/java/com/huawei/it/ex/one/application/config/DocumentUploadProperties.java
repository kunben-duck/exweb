/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

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
    /** 文档 provider 上传时允许透传的 Cookie 请求头最大字符数。 */
    private int forwardCookieMaxLength = 8192;

    public long getMaxUploadSizeBytes() {
        return maxUploadSizeBytes;
    }

    public void setMaxUploadSizeBytes(long maxUploadSizeBytes) {
        this.maxUploadSizeBytes = maxUploadSizeBytes;
    }

    public int getForwardCookieMaxLength() {
        return forwardCookieMaxLength;
    }

    public void setForwardCookieMaxLength(int forwardCookieMaxLength) {
        this.forwardCookieMaxLength = forwardCookieMaxLength;
    }

    public long normalizedMaxUploadSizeBytes() {
        return Math.max(1L, maxUploadSizeBytes);
    }

    public int normalizedForwardCookieMaxLength() {
        return Math.max(0, forwardCookieMaxLength);
    }
}
