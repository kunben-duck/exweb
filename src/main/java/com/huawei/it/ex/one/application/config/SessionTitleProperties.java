package com.huawei.it.ex.one.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;

/** 会话标题自动总结配置。 */
@ConfigurationProperties(prefix = "financeex.session-title")
public class SessionTitleProperties {
    public static final Duration MAX_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 8;
    private static final int MAX_CONCURRENT_REQUESTS = 64;

    private boolean enabled;
    private String baseUrl = "";
    private String path = "/session_title";
    private String timeout = "";
    private String defaultLanguage = "zh-CN";
    private int maxTitleLength = 50;
    private int maxConcurrentRequests = DEFAULT_MAX_CONCURRENT_REQUESTS;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getTimeout() {
        return timeout;
    }

    public void setTimeout(String timeout) {
        this.timeout = timeout;
    }

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    public void setDefaultLanguage(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }

    public int getMaxTitleLength() {
        return maxTitleLength;
    }

    public void setMaxTitleLength(int maxTitleLength) {
        this.maxTitleLength = maxTitleLength;
    }

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public void setMaxConcurrentRequests(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    public String normalizedBaseUrl() {
        return normalize(baseUrl);
    }

    public String normalizedPath() {
        String normalized = normalize(path);
        return normalized == null ? null : normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    public Duration normalizedTimeout() {
        String normalized = normalize(timeout);
        if (normalized == null) {
            return null;
        }
        try {
            Duration parsed = DurationStyle.detectAndParse(normalized);
            return parsed.isZero() || parsed.isNegative() ? null : parsed;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public Duration effectiveRequestTimeout() {
        Duration configured = normalizedTimeout();
        return configured == null || configured.compareTo(MAX_REQUEST_TIMEOUT) > 0
                ? MAX_REQUEST_TIMEOUT
                : configured;
    }

    public int normalizedMaxConcurrentRequests() {
        if (maxConcurrentRequests >= 1 && maxConcurrentRequests <= MAX_CONCURRENT_REQUESTS) {
            return maxConcurrentRequests;
        }
        if (enabled) {
            throw new IllegalStateException(
                    "financeex.session-title.max-concurrent-requests must be between 1 and 64");
        }
        return DEFAULT_MAX_CONCURRENT_REQUESTS;
    }

    public String normalizedDefaultLanguage() {
        String normalized = normalize(defaultLanguage);
        return normalized == null ? "zh-CN" : normalized;
    }

    public String normalizeLanguage(String language) {
        String normalized = normalize(language);
        return normalized == null ? normalizedDefaultLanguage() : normalized;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
