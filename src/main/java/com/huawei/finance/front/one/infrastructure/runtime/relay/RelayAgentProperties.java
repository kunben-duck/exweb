package com.huawei.finance.front.one.infrastructure.runtime.relay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Relay Runtime adapter 配置。
 *
 * <p>{@code provider} 表示 AgentRuntime 类型，当前上线版本内置 {@code relay}。
 * {@code apiAdapter} 表示 Relay Runtime 对接下游 API 的协议 adapter，当前支持
 * {@code relay-stream-http}、{@code relay-websocket} 和 {@code deepseek-chat-completions}。
 * 这样可以把 Runtime 类型、下游 API 协议和请求/响应格式解耦，避免在主编排里出现第三方协议细节。</p>
 */
@ConfigurationProperties(prefix = "financeex.agent-runtime")
public class RelayAgentProperties {
    /** 当前装配的 AgentRuntime 类型，默认 relay。 */
    private String provider = "relay";
    /** Relay API 接入协议 adapter；默认使用真实 Relay stream-http 协议。 */
    private String apiAdapter = "relay-stream-http";
    /** Relay Runtime 服务基础地址。 */
    private String baseUrl = "http://localhost:9000";
    /** Relay Runtime 流式查询接口路径。 */
    private String streamPath = "/v1/agent/runs/stream";
    /** Relay Runtime stop 接口路径，支持 {runId} 占位；为空表示下游暂不支持取消。 */
    private String stopPath = "/v1/agent/runs/{runId}/stop";
    /** Relay Runtime WebSocket 对话接口路径。 */
    private String websocketPath = "/v1/agent/runs/ws";
    /** HTTP adapter 调用外部兼容服务时使用的 Bearer token；必须来自环境变量或密钥系统。 */
    private String apiKey = "";
    /** Chat Completions 兼容模式使用的模型名称。 */
    private String model = "deepseek-v4-pro";
    /** Chat Completions 兼容模式使用的系统提示词。 */
    private String systemPrompt = "You are a helpful assistant.";
    /** Chat Completions 兼容模式是否请求流式响应。 */
    private boolean stream = true;
    /** Chat Completions 兼容模式是否下发 thinking.enabled。 */
    private boolean thinkingEnabled = false;
    /** Chat Completions 兼容模式的推理强度，例如 high。 */
    private String reasoningEffort = "";
    /** 下游 Runtime 是否支持独立取消接口；DeepSeek 替身通常设置为 false。 */
    private boolean cancelSupported = true;
    /** Relay Runtime 单次调用超时时间。 */
    private Duration timeout = Duration.ofSeconds(60);

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiAdapter() {
        return apiAdapter;
    }

    public void setApiAdapter(String apiAdapter) {
        this.apiAdapter = apiAdapter;
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

    public String getWebsocketPath() {
        return websocketPath;
    }

    public void setWebsocketPath(String websocketPath) {
        this.websocketPath = websocketPath;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public boolean isThinkingEnabled() {
        return thinkingEnabled;
    }

    public void setThinkingEnabled(boolean thinkingEnabled) {
        this.thinkingEnabled = thinkingEnabled;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
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

    /**
     * @return 当前生效的 Relay API adapter 名称；为空时回落到正式默认值 relay-stream-http。
     */
    public String selectedApiAdapter() {
        if (hasText(apiAdapter)) {
            return normalize(apiAdapter);
        }
        return "relay-stream-http";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
