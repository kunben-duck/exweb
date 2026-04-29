package com.huawei.finance.front.one.infrastructure.memory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作记忆 Redis 配置。
 *
 * <p>keyPrefix 默认以 fin_ex 开头，满足 Redis key 命名规范。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.memory.working.redis")
public class WorkingMemoryRedisProperties {
    private boolean enabled = true;
    private String keyPrefix = "fin_ex:memory:working";
    private Duration ttl = Duration.ofDays(3);
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

    public Duration getFailureBackoff() {
        return failureBackoff;
    }

    public void setFailureBackoff(Duration failureBackoff) {
        this.failureBackoff = failureBackoff;
    }
}
