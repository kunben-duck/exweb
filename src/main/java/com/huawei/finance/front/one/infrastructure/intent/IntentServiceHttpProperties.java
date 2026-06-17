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
    /** 意图服务基础地址。 */
    private String baseUrl = "http://localhost:9200";
    /** 意图识别接口路径。 */
    private String recognizePath = "/v1/intents/recognize";
    /** 单次意图识别调用超时时间。 */
    private Duration timeout = Duration.ofSeconds(5);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getRecognizePath() {
        return recognizePath;
    }

    public void setRecognizePath(String recognizePath) {
        this.recognizePath = recognizePath;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration normalizedTimeout() {
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(5)
                : timeout;
    }
}
