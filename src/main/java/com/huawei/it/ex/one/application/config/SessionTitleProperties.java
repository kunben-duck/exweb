/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;

/** 会话标题自动总结配置。 */
@ConfigurationProperties(prefix = "financeex.session-title")
public class SessionTitleProperties {
    public static final Duration MAX_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 8;
    private static final int MAX_CONCURRENT_REQUESTS = 64;

    private boolean enabled;
    private Mode mode = Mode.HTTP;
    private final Model model = new Model();
    private String baseUrl = "";
    private String path = "/session_title";
    private String timeout = "";
    private String defaultLanguage = "zh_CN";
    private int maxTitleLength = 50;
    private int maxConcurrentRequests = DEFAULT_MAX_CONCURRENT_REQUESTS;
    private List<String> excludedAppIds = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Model getModel() {
        return model;
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

    public List<String> getExcludedAppIds() {
        return excludedAppIds;
    }

    public void setExcludedAppIds(List<String> excludedAppIds) {
        if (excludedAppIds == null || excludedAppIds.isEmpty()) {
            this.excludedAppIds = List.of();
            return;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        excludedAppIds.stream()
                .map(SessionTitleProperties::normalize)
                .filter(value -> value != null)
                .forEach(normalized::add);
        this.excludedAppIds = List.copyOf(normalized);
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
        return normalized == null ? "zh_CN" : normalized;
    }

    public String normalizeLanguage(String language) {
        String normalized = normalize(language);
        return normalized == null ? normalizedDefaultLanguage() : normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public enum Mode {
        HTTP, MODEL
    }

    public static class Model {
        private String endpoint = "";
        private String name = "";
        private String apiKey = "";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String normalizedEndpoint() {
            return normalize(endpoint);
        }

        public String normalizedName() {
            return normalize(name);
        }

        public String normalizedApiKey() {
            return normalize(apiKey);
        }
    }
}
