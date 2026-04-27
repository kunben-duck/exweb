package com.huawei.finance.front.one.infrastructure.agent.runtime.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "financeex.agent-runtime.providers.relay-agent")
public class RelayAgentProperties {
    private boolean enabled = false;
    private String baseUrl = "http://localhost:9000";
    private String streamPath = "/v1/agent/runs/stream";

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

    public String getStreamPath() {
        return streamPath;
    }

    public void setStreamPath(String streamPath) {
        this.streamPath = streamPath;
    }
}
