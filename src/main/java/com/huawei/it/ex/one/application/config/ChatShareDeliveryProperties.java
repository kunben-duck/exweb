package com.huawei.it.ex.one.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 分享链接发送配置。
 */
@Component
@ConfigurationProperties(prefix = "financeex.share")
public class ChatShareDeliveryProperties {
    /** 分享页 URL 前缀，发送 provider 会在后面拼接 shareId。 */
    private String shareUrlPrefix = "";
    /** 发送行为配置。 */
    private Delivery delivery = new Delivery();

    public String getShareUrlPrefix() {
        return shareUrlPrefix;
    }

    public void setShareUrlPrefix(String shareUrlPrefix) {
        this.shareUrlPrefix = shareUrlPrefix;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public void setDelivery(Delivery delivery) {
        this.delivery = delivery == null ? new Delivery() : delivery;
    }

    public String normalizedShareUrlPrefix() {
        return shareUrlPrefix == null ? "" : shareUrlPrefix.trim();
    }

    public int normalizedContentMaxLength() {
        return Math.max(1, delivery.getContentMaxLength());
    }

    public int normalizedMaxTargets() {
        return Math.max(1, delivery.getMaxTargets());
    }

    public int normalizedMaxConcurrency() {
        return Math.max(1, delivery.getMaxConcurrency());
    }

    public int normalizedForwardCookieMaxLength() {
        return Math.max(0, delivery.getForwardCookieMaxLength());
    }

    /**
     * 发送通用配置。
     */
    public static class Delivery {
        /** 默认分享正文摘要最大长度。 */
        private int contentMaxLength = 200;
        /** 单次发送最多目标数，targetAccounts 与 groupIds 合并计算。 */
        private int maxTargets = 100;
        /** 当前 JVM 内同时执行的分享发送调用上限。 */
        private int maxConcurrency = 20;
        /** 分享发送入口 Cookie 请求头最大透传长度；仅供显式支持 Cookie 的 provider 使用。 */
        private int forwardCookieMaxLength = 8192;
        /** provider 配置集合。 */
        private Providers providers = new Providers();

        public int getContentMaxLength() {
            return contentMaxLength;
        }

        public void setContentMaxLength(int contentMaxLength) {
            this.contentMaxLength = contentMaxLength;
        }

        public int getMaxTargets() {
            return maxTargets;
        }

        public void setMaxTargets(int maxTargets) {
            this.maxTargets = maxTargets;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public int getForwardCookieMaxLength() {
            return forwardCookieMaxLength;
        }

        public void setForwardCookieMaxLength(int forwardCookieMaxLength) {
            this.forwardCookieMaxLength = forwardCookieMaxLength;
        }

        public Providers getProviders() {
            return providers;
        }

        public void setProviders(Providers providers) {
            this.providers = providers == null ? new Providers() : providers;
        }
    }

    /**
     * 分享发送 provider 配置集合。
     */
    public static class Providers {
        /** WeLink provider 配置。 */
        private Welink welink = new Welink();

        public Welink getWelink() {
            return welink;
        }

        public void setWelink(Welink welink) {
            this.welink = welink == null ? new Welink() : welink;
        }
    }

    /**
     * WeLink 分享发送配置。
     */
    public static class Welink {
        private static final int MAX_NORMALIZED_RETRIES = 10;

        /** 是否启用 WeLink 分享发送。 */
        private boolean enabled = false;
        /** WeLink API 基础地址。 */
        private String baseUrl = "";
        /** WeLink 分享请求 Referer；为空时使用 base-url。 */
        private String referer = "";
        /** WeLink 发送接口 path。 */
        private String sendPath = "";
        /** WeLink HTTP 调用超时时间。 */
        private Duration timeout = Duration.ofSeconds(5);
        /** WeLink 调用失败后的最大重试次数；不包含首次调用，运行时会限制到安全上限。 */
        private int maxRetries = 3;
        /** WeLink 成功状态字段。 */
        private String successStatusField = "status";
        /** WeLink 成功状态值。 */
        private String successStatusValue = "200";

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

        public String getReferer() {
            return referer;
        }

        public void setReferer(String referer) {
            this.referer = referer;
        }

        public String getSendPath() {
            return sendPath;
        }

        public void setSendPath(String sendPath) {
            this.sendPath = sendPath;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public String getSuccessStatusField() {
            return successStatusField;
        }

        public void setSuccessStatusField(String successStatusField) {
            this.successStatusField = successStatusField;
        }

        public String getSuccessStatusValue() {
            return successStatusValue;
        }

        public void setSuccessStatusValue(String successStatusValue) {
            this.successStatusValue = successStatusValue;
        }

        public Duration normalizedTimeout() {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                return Duration.ofSeconds(5);
            }
            return timeout;
        }

        public int normalizedMaxRetries() {
            return Math.min(MAX_NORMALIZED_RETRIES, Math.max(0, maxRetries));
        }

        public String normalizedReferer() {
            String configured = referer == null ? "" : referer.trim();
            if (!configured.isBlank()) {
                return configured;
            }
            return baseUrl == null ? "" : baseUrl.trim();
        }
    }
}
