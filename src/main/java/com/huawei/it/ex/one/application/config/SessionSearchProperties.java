/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 会话正文关键字搜索的数据库保护配置。 */
@Validated
@ConfigurationProperties(prefix = "financeex.session-search")
public class SessionSearchProperties {
    @Min(1)
    @Max(30)
    private int databaseQueryTimeoutSeconds = 2;

    public int getDatabaseQueryTimeoutSeconds() {
        return databaseQueryTimeoutSeconds;
    }

    public void setDatabaseQueryTimeoutSeconds(int databaseQueryTimeoutSeconds) {
        this.databaseQueryTimeoutSeconds = databaseQueryTimeoutSeconds;
    }
}
