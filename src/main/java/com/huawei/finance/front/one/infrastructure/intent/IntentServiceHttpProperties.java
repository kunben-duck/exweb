package com.huawei.finance.front.one.infrastructure.intent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 意图服务 HTTP adapter 配置。
 *
 * <p>该配置只描述下游 HTTP 边界，入参和出参字段映射由独立 mapper 承载，避免下游协议变化时
 * 反复修改 HTTP 调用编排。</p>
 */
@ConfigurationProperties(prefix = "financeex.intent")
public class IntentServiceHttpProperties {
    private static final int MAX_NORMALIZED_RETRIES = 10;

    /** 意图服务基础地址。 */
    private String baseUrl = "";
    /** 意图入口名称。 */
    private String accessName = "";
    /** 意图响应 items[].accessName 转换为 DomainAgent skillId 时移除的可选前缀。 */
    private String responseAccessNamePrefix = "";
    /** 意图识别接口路径。 */
    private String recognizePath = "/intent-recognition-configuration/getIntentDecision";
    /** 是否要求意图服务返回 trace。 */
    private boolean trace = false;
    /** 单次意图识别调用超时时间。 */
    private Duration timeout = Duration.ofSeconds(5);
    /** 意图服务调用失败后的最大重试次数；不包含首次调用，运行时会限制到安全上限。 */
    private int maxRetries = 3;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAccessName() {
        return accessName;
    }

    public void setAccessName(String accessName) {
        this.accessName = accessName;
    }

    public String getResponseAccessNamePrefix() {
        return responseAccessNamePrefix;
    }

    public void setResponseAccessNamePrefix(String responseAccessNamePrefix) {
        this.responseAccessNamePrefix = responseAccessNamePrefix;
    }

    public String getRecognizePath() {
        return recognizePath;
    }

    public void setRecognizePath(String recognizePath) {
        this.recognizePath = recognizePath;
    }

    public boolean isTrace() {
        return trace;
    }

    public void setTrace(boolean trace) {
        this.trace = trace;
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

    public Duration normalizedTimeout() {
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(5)
                : timeout;
    }

    public int normalizedMaxRetries() {
        return Math.min(MAX_NORMALIZED_RETRIES, Math.max(0, maxRetries));
    }

}
