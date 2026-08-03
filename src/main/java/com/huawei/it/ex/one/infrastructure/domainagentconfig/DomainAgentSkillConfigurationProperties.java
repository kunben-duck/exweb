package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;

/** 默认技能配置 Provider 的调用约束。 */
@ConfigurationProperties(prefix = "financeex.domain-agent-skill-config")
public class DomainAgentSkillConfigurationProperties {
    private String baseUrl = "";
    private String queryPath = "";
    private String timeout = "2s";

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

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
