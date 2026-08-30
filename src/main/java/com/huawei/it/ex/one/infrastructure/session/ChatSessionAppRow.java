/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.session;

import java.time.Instant;

/**
 * 会话应用分类轻量查询行。
 */
public class ChatSessionAppRow {
    private String appId;
    private String appName;
    private Instant latestActivityAt;

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public Instant getLatestActivityAt() {
        return latestActivityAt;
    }

    public void setLatestActivityAt(Instant latestActivityAt) {
        this.latestActivityAt = latestActivityAt;
    }
}
