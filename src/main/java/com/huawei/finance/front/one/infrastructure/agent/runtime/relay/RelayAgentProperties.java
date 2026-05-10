package com.huawei.finance.front.one.infrastructure.agent.runtime.relay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "financeex.agent-runtime")
public class RelayAgentProperties {
    private String baseUrl = "http://localhost:9000";
    private String streamPath = "/v1/agent/runs/stream";
    private Duration timeout = Duration.ofSeconds(60);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getStreamPath() {
        return streamPath;
    }

    public void setStreamPath(String streamPath) {
        this.streamPath = streamPath;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
