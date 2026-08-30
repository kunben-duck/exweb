/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.memory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 短期消息存储降级配置。
 *
 * <p>短期消息优先写数据库，Redis 只作为热缓存。本配置控制数据库不可用时是否允许降级。</p>
 */
@Validated
@ConfigurationProperties(prefix = "financeex.memory.short-term.storage")
public class ShortTermMemoryStorageProperties {
    /** true 表示数据库写入失败时直接抛错，false 表示允许短期降级到 Redis 缓存。 */
    private boolean databaseRequired = false;
    /** 数据库失败后的退避窗口，避免每条消息都同步探测数据库。 */
    private Duration databaseFailureBackoff = Duration.ofSeconds(30);
    /** 短期记忆数据库回源的事务及 JDBC Statement 超时秒数。 */
    @Min(1)
    @Max(30)
    private int databaseQueryTimeoutSeconds = 2;

    public boolean isDatabaseRequired() {
        return databaseRequired;
    }

    public void setDatabaseRequired(boolean databaseRequired) {
        this.databaseRequired = databaseRequired;
    }

    public Duration getDatabaseFailureBackoff() {
        return databaseFailureBackoff;
    }

    public void setDatabaseFailureBackoff(Duration databaseFailureBackoff) {
        this.databaseFailureBackoff = databaseFailureBackoff;
    }

    public int getDatabaseQueryTimeoutSeconds() {
        return databaseQueryTimeoutSeconds;
    }

    public void setDatabaseQueryTimeoutSeconds(int databaseQueryTimeoutSeconds) {
        this.databaseQueryTimeoutSeconds = databaseQueryTimeoutSeconds;
    }
}
