package com.huawei.finance.front.one.infrastructure.runtime.relay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Relay Runtime adapter 配置。
 *
 * <p>Relay 是可同时注册的 AgentRuntime provider 之一。
 * Relay 下游接入通过 {@code relay.adapter} 在二级 adapter 中选择，默认仍为 streamable HTTP。
 * 新增或替换 Relay 协议时只扩展 {@link RelayRuntimeProtocolAdapter}，不改主编排。</p>
 */
@ConfigurationProperties(prefix = "financeex.agent-runtime")
public class RelayAgentProperties {
    /** 没有明确 provider 绑定时使用的默认 Runtime provider。 */
    private String defaultProvider = "relay";
    /** Relay Runtime 服务基础地址。 */
    private String baseUrl = "";
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
    /** Relay 二级协议 adapter 配置。 */
    private Relay relay = new Relay();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
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

    public Relay getRelay() {
        return relay;
    }

    public void setRelay(Relay relay) {
        this.relay = relay == null ? new Relay() : relay;
    }

    /**
     * Relay provider 内部 adapter 配置。
     */
    public static class Relay {
        /** 是否启用 Relay Runtime provider。 */
        private boolean enabled = true;
        /** 当前使用的 Relay API adapter，默认保持 stream-http 主链路。 */
        private String adapter = "relay-stream-http";
        /** WebSocket adapter 配置，仅在 adapter=relay-websocket 时使用。 */
        private WebSocket websocket = new WebSocket();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAdapter() {
            return adapter;
        }

        public void setAdapter(String adapter) {
            this.adapter = adapter;
        }

        public WebSocket getWebsocket() {
            return websocket;
        }

        public void setWebsocket(WebSocket websocket) {
            this.websocket = websocket == null ? new WebSocket() : websocket;
        }
    }

    /**
     * Relay WebSocket 普通问答 adapter 配置。
     */
    public static class WebSocket {
        /** Relay WebSocket URL 前缀，adapter 会在后面追加 /{clientId}。 */
        private String url = "";
        /** Relay appMode，普通问答默认 delegate。 */
        private String appMode = "delegate";
        /** 建立下游 WebSocket 连接的超时时间。 */
        private Duration connectTimeout = Duration.ofSeconds(5);
        /** 等待 config 初始化响应闭合的超时时间，超时后不会发送 user-message。 */
        private Duration configHandshakeTimeout = Duration.ofSeconds(10);
        /** 跨实例 stop 临时连接发送 interrupt 后等待 Relay paused 确认的最长时间。 */
        private Duration interruptAckTimeout = Duration.ofSeconds(5);
        /** 控制类临时连接等待下游下一帧的空闲超时时间；普通问答阶段不再使用该值判定失败。 */
        private Duration idleTimeout = Duration.ofSeconds(60);
        /** user-message 发出后的最长执行时间，超过后 ChatService 主动失败闭合本轮 run。 */
        private Duration maxRunDuration = Duration.ofMinutes(30);
        /** user-message 发出后的 Relay WebSocket 心跳发送间隔。 */
        private Duration heartbeatInterval = Duration.ofSeconds(20);
        /** user-message 发出后等待任意下游回包的最长时间，<=0 表示禁用该活性检测。 */
        private Duration heartbeatResponseTimeout = Duration.ofSeconds(90);
        /** 单个下游 WebSocket 文本帧最大字节数。 */
        private DataSize maxFrameBytes = DataSize.ofMegabytes(1);

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getAppMode() {
            return appMode;
        }

        public void setAppMode(String appMode) {
            this.appMode = appMode;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getConfigHandshakeTimeout() {
            return configHandshakeTimeout;
        }

        public void setConfigHandshakeTimeout(Duration configHandshakeTimeout) {
            this.configHandshakeTimeout = configHandshakeTimeout;
        }

        public Duration getInterruptAckTimeout() {
            return interruptAckTimeout;
        }

        public void setInterruptAckTimeout(Duration interruptAckTimeout) {
            this.interruptAckTimeout = interruptAckTimeout;
        }

        public Duration getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
        }

        public Duration getMaxRunDuration() {
            return maxRunDuration;
        }

        public void setMaxRunDuration(Duration maxRunDuration) {
            this.maxRunDuration = maxRunDuration;
        }

        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        public Duration getHeartbeatResponseTimeout() {
            return heartbeatResponseTimeout;
        }

        public void setHeartbeatResponseTimeout(Duration heartbeatResponseTimeout) {
            this.heartbeatResponseTimeout = heartbeatResponseTimeout;
        }

        public DataSize getMaxFrameBytes() {
            return maxFrameBytes;
        }

        public void setMaxFrameBytes(DataSize maxFrameBytes) {
            this.maxFrameBytes = maxFrameBytes;
        }
    }

}
