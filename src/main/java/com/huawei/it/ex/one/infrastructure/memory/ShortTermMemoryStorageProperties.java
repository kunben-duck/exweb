package com.huawei.it.ex.one.infrastructure.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 短期消息存储降级配置。
 *
 * <p>短期消息优先写数据库，Redis 只作为热缓存。本配置控制数据库不可用时是否允许降级。</p>
 */
@ConfigurationProperties(prefix = "financeex.memory.short-term.storage")
public class ShortTermMemoryStorageProperties {
    /** true 表示数据库写入失败时直接抛错，false 表示允许短期降级到 Redis 缓存。 */
    private boolean databaseRequired = false;
    /** 数据库失败后的退避窗口，避免每条消息都同步探测数据库。 */
    private Duration databaseFailureBackoff = Duration.ofSeconds(30);

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
}
