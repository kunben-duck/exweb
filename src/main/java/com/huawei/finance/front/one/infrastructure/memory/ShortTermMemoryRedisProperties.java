package com.huawei.finance.front.one.infrastructure.memory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 短期记忆 Redis 缓存配置。
 *
 * <p>该配置只影响 infra 层缓存行为，application 层仍然只依赖 ChatMessageRepository 抽象。
 * {@code enabled} 表示是否启用短期记忆能力，{@code cacheEnabled} 表示启用短期记忆时是否使用 Redis 热缓存。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.memory.short-term")
public class ShortTermMemoryRedisProperties {
    /** 是否启用短期最近问答记忆能力，默认关闭。 */
    private boolean enabled = false;
    /** 是否启用 Redis 热缓存；短期记忆关闭时即使该值为 true 也不会访问 Redis。 */
    private boolean cacheEnabled = true;
    /** Redis 逻辑 key 前缀，必须以 fin_ex 开头；运行时会自动插入环境段。 */
    private String redisKeyPrefix = "fin_ex:memory:short_term";
    /** 最近消息缓存 TTL。 */
    private Duration ttl = Duration.ofDays(3);
    /** 每个会话最多缓存的消息条数。 */
    private int maxCachedMessages = 200;
    /** Redis 连接失败后的退避时间。 */
    private Duration failureBackoff = Duration.ofSeconds(30);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    public String getRedisKeyPrefix() {
        return redisKeyPrefix;
    }

    public void setRedisKeyPrefix(String redisKeyPrefix) {
        this.redisKeyPrefix = redisKeyPrefix;
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
