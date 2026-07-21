package com.huawei.it.ex.one.runtime.infrastructure.relay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Relay Runtime WebSocket 配置。
 *
 * <p>Relay 是可同时注册的 AgentRuntime provider 之一，当前唯一通信方式为 WebSocket。</p>
 */
@ConfigurationProperties(prefix = "financeex.agent-runtime")
public class RelayAgentProperties {
    /** Relay provider 配置。 */
    private Relay relay = new Relay();

    public Relay getRelay() {
        return relay;
    }

    public void setRelay(Relay relay) {
        this.relay = relay == null ? new Relay() : relay;
    }

    /**
     * Relay provider 配置。
     */
    public static class Relay {
        /** 是否启用 Relay Runtime provider。 */
        private boolean enabled = true;
        /** WebSocket 通信配置。 */
        private WebSocket websocket = new WebSocket();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public WebSocket getWebsocket() {
            return websocket;
        }

        public void setWebsocket(WebSocket websocket) {
            this.websocket = websocket == null ? new WebSocket() : websocket;
        }
    }

    /**
     * Relay WebSocket 普通问答、Interaction 与 stop 配置。
     */
    public static class WebSocket {
        /** Relay WebSocket URL 前缀，adapter 会在后面追加 /{clientId}。 */
        private String url = "";
        /** Relay appMode，普通问答默认 delegate。 */
        private String appMode = "delegate";
        /** 建立下游 WebSocket 连接的超时时间。 */
        private Duration connectTimeout = Duration.ofSeconds(5);
        /** 分别限制 HTTP Upgrade opening handshake 和 config -> session-ready 的等待时间。 */
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
