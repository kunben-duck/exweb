/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;

/** 默认技能配置 Provider 的调用约束。 */
@ConfigurationProperties(prefix = "financeex.domain-agent-skill-config")
public class DomainAgentSkillConfigurationProperties {
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);
    private static final String DEFAULT_CACHE_KEY_PREFIX = "fin_ex:domain_agent_skill_config:v1";

    private String baseUrl = "";
    private String queryPath = "";
    private String timeout = "2s";
    private boolean cacheEnabled = true;
    private Duration cacheTtl = DEFAULT_CACHE_TTL;
    private String cacheKeyPrefix = DEFAULT_CACHE_KEY_PREFIX;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getQueryPath() {
        return queryPath;
    }

    public void setQueryPath(String queryPath) {
        this.queryPath = queryPath;
    }

    public String getTimeout() {
        return timeout;
    }

    public void setTimeout(String timeout) {
        this.timeout = timeout;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
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

    public String normalizedBaseUrl() {
        return normalize(baseUrl);
    }

    public String normalizedQueryPath() {
        return normalize(queryPath);
    }

    public Duration normalizedTimeout() {
        if (timeout == null || timeout.isBlank()) {
            return null;
        }
        try {
            Duration parsed = DurationStyle.detectAndParse(timeout.trim());
            return parsed.isZero() || parsed.isNegative() ? null : parsed;
        } catch (IllegalArgumentException ex) {
            return null;
        }
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

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
