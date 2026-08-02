package com.huawei.it.ex.one.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** DomainAgent assistant 历史数据留存配置。 */
@Component
@ConfigurationProperties(prefix = "financeex.agent-data-persistence")
public class AgentDataPersistenceProperties {
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);
    private static final String DEFAULT_CACHE_KEY_PREFIX = "fin_ex:agent_data_persistence";
    private static final String DEFAULT_PLACEHOLDER_CONTENT =
            "根据数据留存策略，本次回答不在消息历史中展示。";

    private boolean enabled;
    private Duration cacheTtl = DEFAULT_CACHE_TTL;
    private String cacheKeyPrefix = DEFAULT_CACHE_KEY_PREFIX;
    private String placeholderContent = DEFAULT_PLACEHOLDER_CONTENT;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public String getCacheKeyPrefix() {
        return cacheKeyPrefix;
    }

    public void setCacheKeyPrefix(String cacheKeyPrefix) {
        this.cacheKeyPrefix = cacheKeyPrefix;
    }

    public String getPlaceholderContent() {
        return placeholderContent;
    }

    public void setPlaceholderContent(String placeholderContent) {
        this.placeholderContent = placeholderContent;
    }

    public Duration normalizedCacheTtl() {
        return cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()
                ? DEFAULT_CACHE_TTL
                : cacheTtl;
    }

    public String normalizedCacheKeyPrefix() {
        return cacheKeyPrefix == null || cacheKeyPrefix.isBlank()
                ? DEFAULT_CACHE_KEY_PREFIX
                : cacheKeyPrefix.trim();
    }

    public String normalizedPlaceholderContent() {
        return placeholderContent == null || placeholderContent.isBlank()
                ? DEFAULT_PLACEHOLDER_CONTENT
                : placeholderContent.trim();
    }
}
