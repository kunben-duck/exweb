package com.huawei.finance.front.one.infrastructure.task;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TaskCard Redis 热缓存配置。
 *
 * <p>active key 用于快速定位当前会话正在处理的任务；card key 用于保留任务快照。
 * 两类 key 都必须使用 fin_ex 前缀，避免与其他系统缓存命名冲突。</p>
 */
@ConfigurationProperties(prefix = "financeex.task.redis")
public class TaskCardRedisProperties {
    /** 当前 active task Redis key 前缀。 */
    private String activeKeyPrefix = "fin_ex:task:active";
    /** 单个 TaskCard 快照 Redis key 前缀。 */
    private String cardKeyPrefix = "fin_ex:task:card";
    /** TaskCard 热缓存 TTL，事实状态仍以 openGauss 为准。 */
    private Duration ttl = Duration.ofDays(3);

    public String getActiveKeyPrefix() {
        return activeKeyPrefix;
    }

    public void setActiveKeyPrefix(String activeKeyPrefix) {
        this.activeKeyPrefix = activeKeyPrefix;
    }

    public String getCardKeyPrefix() {
        return cardKeyPrefix;
    }

    public void setCardKeyPrefix(String cardKeyPrefix) {
        this.cardKeyPrefix = cardKeyPrefix;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
