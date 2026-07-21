package com.huawei.it.ex.one.application.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Region-based service access configuration.
 */
@Component
@ConfigurationProperties(prefix = "financeex.regional-access")
public class RegionalAccessProperties {
    private static final Duration DEFAULT_LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofDays(1);

    private boolean enabled = true;
    private int interceptorOrder = Ordered.LOWEST_PRECEDENCE;
    private String ipHeaderName = "X-Real-IP";
    private Duration lookupTimeout = DEFAULT_LOOKUP_TIMEOUT;
    private Duration cacheTtl = DEFAULT_CACHE_TTL;
    private long cacheMaximumSize = 10_000;
    private int maxConcurrentLookups = 16;
    private String hrBaseUrl = "";
    private String hrAppId = "";
    private String ipBaseUrl = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getInterceptorOrder() {
        return interceptorOrder;
    }

    public void setInterceptorOrder(int interceptorOrder) {
        this.interceptorOrder = interceptorOrder;
    }

    public String getIpHeaderName() {
        return ipHeaderName;
    }

    public void setIpHeaderName(String ipHeaderName) {
        this.ipHeaderName = ipHeaderName;
    }

    public Duration getLookupTimeout() {
        return lookupTimeout;
    }

    public void setLookupTimeout(Duration lookupTimeout) {
        this.lookupTimeout = lookupTimeout;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public long getCacheMaximumSize() {
        return cacheMaximumSize;
    }

    public void setCacheMaximumSize(long cacheMaximumSize) {
        this.cacheMaximumSize = cacheMaximumSize;
    }

    public int getMaxConcurrentLookups() {
        return maxConcurrentLookups;
    }

    public void setMaxConcurrentLookups(int maxConcurrentLookups) {
        this.maxConcurrentLookups = maxConcurrentLookups;
    }

    public String getHrBaseUrl() {
        return hrBaseUrl;
    }

    public void setHrBaseUrl(String hrBaseUrl) {
        this.hrBaseUrl = hrBaseUrl;
    }

    public String getHrAppId() {
        return hrAppId;
    }

    public void setHrAppId(String hrAppId) {
        this.hrAppId = hrAppId;
    }

    public String getIpBaseUrl() {
        return ipBaseUrl;
    }

    public void setIpBaseUrl(String ipBaseUrl) {
        this.ipBaseUrl = ipBaseUrl;
    }

    public String normalizedIpHeaderName() {
        return textOrDefault(ipHeaderName, "X-Real-IP");
    }

    public Duration normalizedLookupTimeout() {
        return positiveDuration(lookupTimeout, DEFAULT_LOOKUP_TIMEOUT);
    }

    public Duration normalizedCacheTtl() {
        return positiveDuration(cacheTtl, DEFAULT_CACHE_TTL);
    }

    public long normalizedCacheMaximumSize() {
        return Math.max(1, cacheMaximumSize);
    }

    public int normalizedMaxConcurrentLookups() {
        return Math.max(1, maxConcurrentLookups);
    }

    public String normalizedHrBaseUrl() {
        return trimToEmpty(hrBaseUrl);
    }

    public String normalizedHrAppId() {
        return trimToEmpty(hrAppId);
    }

    public String normalizedIpBaseUrl() {
        return trimToEmpty(ipBaseUrl);
    }

    private Duration positiveDuration(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private String textOrDefault(String value, String fallback) {
        String normalized = trimToEmpty(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
