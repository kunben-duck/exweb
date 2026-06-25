package com.huawei.finance.front.one.infrastructure.runtime.relay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Relay Runtime adapter 配置。
 *
 * <p>{@code provider} 表示 AgentRuntime 类型，当前上线版本内置 {@code relay}。
 * Relay 下游接入当前固定为 streamable HTTP。二级 adapter 防腐层仍然保留在代码结构中，
 * 未来新增其他 Relay 协议实现时新增 {@link RelayRuntimeProtocolAdapter} 即可，不改主编排。</p>
 */
@ConfigurationProperties(prefix = "financeex.agent-runtime")
public class RelayAgentProperties {
    /** 当前装配的 AgentRuntime 类型，默认 relay。 */
    private String provider = "relay";
    /** Relay Runtime 服务基础地址。 */
    private String baseUrl = "http://localhost:9000";
    /** Relay Runtime 流式查询接口路径。 */
    private String streamPath = "/v1/agent/runs/stream";
    /** Relay Runtime stop 接口路径，支持 {runId} 占位；为空表示下游暂不支持取消。 */
    private String stopPath = "/v1/agent/runs/{runId}/stop";
    /** 下游 Runtime 是否支持独立取消接口。 */
    private boolean cancelSupported = true;
    /** Relay Runtime 单次调用超时时间。 */
    private Duration timeout = Duration.ofSeconds(60);
    /** Relay 单个响应 frame 的 WebClient codec 内存上限。 */
    private DataSize maxInMemorySize = DataSize.ofMegabytes(1);

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

    public boolean isCancelSupported() {
        return cancelSupported;
    }

    public void setCancelSupported(boolean cancelSupported) {
        this.cancelSupported = cancelSupported;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public DataSize getMaxInMemorySize() {
        return maxInMemorySize;
    }

    public void setMaxInMemorySize(DataSize maxInMemorySize) {
        this.maxInMemorySize = maxInMemorySize;
    }

}
