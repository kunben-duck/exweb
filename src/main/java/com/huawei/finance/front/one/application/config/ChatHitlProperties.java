package com.huawei.finance.front.one.application.config;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * HITL 等待用户输入配置。
 *
 * <p>等待态过期由访问时懒处理，不启动额外定时任务，避免为了清理过期澄清请求引入后台 DB 压力。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.chat-hitl")
public class ChatHitlProperties {
    /** 默认等待用户输入的有效期；配置为 0 或负数时表示不过期。 */
    private Duration defaultExpireDuration = Duration.ofHours(24);

    public Duration getDefaultExpireDuration() {
        return defaultExpireDuration;
    }

    public void setDefaultExpireDuration(Duration defaultExpireDuration) {
        this.defaultExpireDuration = defaultExpireDuration;
    }

    /**
     * 计算等待请求过期时间。
     *
     * @param createdAt 等待请求创建时间。
     * @return 过期时间；为空表示不过期。
     */
    public Instant expiresAt(Instant createdAt) {
        Duration normalized = defaultExpireDuration;
        if (createdAt == null || normalized == null || normalized.isZero() || normalized.isNegative()) {
            return null;
        }
        return createdAt.plus(normalized);
    }
}
