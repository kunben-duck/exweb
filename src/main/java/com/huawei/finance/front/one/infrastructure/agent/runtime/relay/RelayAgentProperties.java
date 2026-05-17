package com.huawei.finance.front.one.infrastructure.agent.runtime.relay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Relay Runtime adapter 配置。
 *
 * <p>{@code provider} 表示 AgentRuntime 类型，当前上线版本内置 {@code relay}。
 * {@code protocol} 表示该 Runtime 类型下的传输协议，Relay 支持 {@code http-streamable}
 * 和 {@code websocket}。两者分级配置，避免把 Runtime 类型与协议揉成复合 provider 名称。</p>
 */
@ConfigurationProperties(prefix = "financeex.agent-runtime")
public class RelayAgentProperties {
    /** 当前装配的 AgentRuntime 类型，默认 relay。 */
    private String provider = "relay";
    /** Relay Runtime 传输协议，默认 http-streamable，可选 websocket。 */
    private String protocol = "http-streamable";
    /** Relay Runtime 服务基础地址。 */
    private String baseUrl = "http://localhost:9000";
    /** Relay Runtime 流式查询接口路径。 */
    private String streamPath = "/v1/agent/runs/stream";
    /** Relay Runtime stop 接口路径，支持 {runId} 占位；为空表示下游暂不支持取消。 */
    private String stopPath = "/v1/agent/runs/{runId}/stop";
    /** Relay Runtime WebSocket 完整地址；为空时由 baseUrl + websocketPath 推导。 */
    private String websocketUrl = "";
    /** Relay Runtime WebSocket 对话接口路径。 */
    private String websocketPath = "/v1/agent/runs/ws";
    /** Relay Runtime 单次调用超时时间。 */
    private Duration timeout = Duration.ofSeconds(60);

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
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

    public String getWebsocketUrl() {
        return websocketUrl;
    }

    public void setWebsocketUrl(String websocketUrl) {
        this.websocketUrl = websocketUrl;
    }

    public String getWebsocketPath() {
        return websocketPath;
    }

    public void setWebsocketPath(String websocketPath) {
        this.websocketPath = websocketPath;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
