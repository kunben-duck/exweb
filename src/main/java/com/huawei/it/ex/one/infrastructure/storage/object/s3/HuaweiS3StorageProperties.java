/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.storage.object.s3;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 华为 OBS/S3 客户端配置。
 */
@ConfigurationProperties(prefix = "financeex.storage.huawei-s3")
public class HuaweiS3StorageProperties {
    private String endpoint = "";
    private String accessKey = "";
    private String secretKey = "";
    private boolean pathStyleAccessEnabled;
    private int maxConnections = 200;
    private int connectionTimeoutMs = 10_000;
    private int socketTimeoutMs = 30_000;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public boolean isPathStyleAccessEnabled() {
        return pathStyleAccessEnabled;
    }

    public void setPathStyleAccessEnabled(boolean pathStyleAccessEnabled) {
        this.pathStyleAccessEnabled = pathStyleAccessEnabled;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(int connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public int getSocketTimeoutMs() {
        return socketTimeoutMs;
    }

    public void setSocketTimeoutMs(int socketTimeoutMs) {
        this.socketTimeoutMs = socketTimeoutMs;
    }
}
