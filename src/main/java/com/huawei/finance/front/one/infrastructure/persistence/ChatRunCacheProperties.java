package com.huawei.finance.front.one.infrastructure.persistence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ChatRun Redis 热缓存配置。
 */
@ConfigurationProperties(prefix = "financeex.chat-run")
public class ChatRunCacheProperties {
    /** active run Redis key 前缀，必须以 fin_ex 开头。 */
    private String activeKeyPrefix = "fin_ex:chat_run:active";
    /** cancel flag Redis key 前缀，必须以 fin_ex 开头。 */
    private String cancelKeyPrefix = "fin_ex:chat_run:cancel";
    /** active run 热缓存 TTL。 */
    private Duration activeTtl = Duration.ofHours(6);
    /** cancel flag TTL，用于跨 JVM 阻断迟到事件。 */
    private Duration cancelTtl = Duration.ofHours(1);

    public String getActiveKeyPrefix() {
        return activeKeyPrefix;
    }

    public void setActiveKeyPrefix(String activeKeyPrefix) {
        this.activeKeyPrefix = activeKeyPrefix;
    }

    public String getCancelKeyPrefix() {
        return cancelKeyPrefix;
    }

    public void setCancelKeyPrefix(String cancelKeyPrefix) {
        this.cancelKeyPrefix = cancelKeyPrefix;
    }

    public Duration getActiveTtl() {
        return activeTtl;
    }

    public void setActiveTtl(Duration activeTtl) {
        this.activeTtl = activeTtl;
    }

    public Duration getCancelTtl() {
        return cancelTtl;
    }

    public void setCancelTtl(Duration cancelTtl) {
        this.cancelTtl = cancelTtl;
    }
}
