package com.huawei.finance.front.one.infrastructure.agent.runtime.relay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Relay Runtime HTTP adapter 配置。
 *
 * <p>provider 配置项用于选择当前装配的 AgentRuntime 实现，默认值为 relay。
 * 本类只保存 Relay adapter 自己需要的 HTTP 地址、路径和超时时间。</p>
 */
@ConfigurationProperties(prefix = "financeex.agent-runtime")
public class RelayAgentProperties {
    /** 当前装配的 AgentRuntime provider 编码，默认 relay。 */
    private String provider = "relay";
    /** Relay Runtime 服务基础地址。 */
    private String baseUrl = "http://localhost:9000";
    /** Relay Runtime 流式查询接口路径。 */
    private String streamPath = "/v1/agent/runs/stream";
    /** Relay Runtime stop 接口路径，支持 {runId} 占位；为空表示下游暂不支持取消。 */
    private String stopPath = "/v1/agent/runs/{runId}/stop";
    /** Relay Runtime 单次 HTTP 调用超时时间。 */
    private Duration timeout = Duration.ofSeconds(60);

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
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

    public String getStopPath() {
        return stopPath;
    }

    public void setStopPath(String stopPath) {
        this.stopPath = stopPath;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
