package com.huawei.finance.front.one.infrastructure.memory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 短期记忆 Redis 缓存配置。
 *
 * <p>该配置只影响 infra 层缓存行为，application 层仍然只依赖 ChatMessageRepository 抽象。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.memory.short-term.redis")
public class ShortTermMemoryRedisProperties {
    private boolean enabled = true;
    private String keyPrefix = "fin_ex:memory:short_term";
    private Duration ttl = Duration.ofDays(3);
    private int maxCachedMessages = 200;
    private Duration failureBackoff = Duration.ofSeconds(30);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getMaxCachedMessages() {
        return maxCachedMessages;
    }

    public void setMaxCachedMessages(int maxCachedMessages) {
        this.maxCachedMessages = maxCachedMessages;
    }

    public Duration getFailureBackoff() {
        return failureBackoff;
    }

    public void setFailureBackoff(Duration failureBackoff) {
        this.failureBackoff = failureBackoff;
    }
}
