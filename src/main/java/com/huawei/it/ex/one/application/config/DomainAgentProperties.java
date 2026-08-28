package com.huawei.it.ex.one.application.config;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 财经领域 DomainAgent 指定调用配置。
 *
 * <p>该配置只用于前端显式选择 domainAgentId 的领域 Agent 调用路径。默认关闭，避免未配置
 * DomainAgent 服务地址时误把普通聊天流量路由到指定领域 Agent。</p>
 */
@ConfigurationProperties(prefix = "financeex.domain-agent")
public class DomainAgentProperties {
    private static final int DEFAULT_MAX_PENDING_FRAME_BYTES = 256 * 1024;

    /** 是否启用 DomainAgent 指定调用能力。 */
    private boolean enabled = false;
    /** DomainAgent 服务基础地址。 */
    private String baseUrl = "";
    /** DomainAgent HTTP 请求 Referer；为空时回退到 baseUrl。 */
    private String referer = "";
    /** DomainAgent chat 流式接口路径。 */
    private String chatPath = "/api/chat";
    /** DomainAgent stop 接口路径；为空表示不支持下游取消。 */
    private String stopPath = "";
    /** DomainAgent stop 请求超时时间；保留旧配置兼容。 */
    private Duration timeout = Duration.ofSeconds(120);
    /** DomainAgent 首个原始响应 chunk 及相邻 chunk 之间的空闲超时。 */
    private Duration streamIdleTimeout = Duration.ofSeconds(300);
    /** DomainAgent 查询从 HTTP 订阅开始计算的绝对总超时。 */
    private Duration streamTotalTimeout = Duration.ofMinutes(15);
    /** 单次 DomainAgent 调用最大附件数。 */
    private int maxAttachments = 10;
    /** 单个完整或未完成 DomainAgent 流式 frame 的最大字节数，防止下游异常大 JSON 导致 OOM。 */
    private int maxPendingFrameBytes = DEFAULT_MAX_PENDING_FRAME_BYTES;
    /**
     * 兼容旧配置保留；DomainAgent 结构化响应现统一在单帧上限内完整输出。
     *
     * @deprecated 不再用于 DomainAgent 对外事件分片。
     */
    @Deprecated(since = "1.0.0", forRemoval = false)
    private int maxFragmentBytes = 8192;
    /** 同一 run 内拒答后最多重新路由次数。 */
    private int maxReroutes = 3;
    /** 手动来源 DomainAgent 拒答后是否跳过用户确认并自动切换到重意图目标。 */
    private boolean refusalAutoSwitchEnabled = false;
    /** DomainAgent 控制事件数据库与缓存 IO 的隔离线程数。 */
    private int controlIoExecutorMaxSize = 2;
    /** DomainAgent 控制事件 IO 调度器队列容量。 */
    private int controlIoExecutorQueueCapacity = 128;
    /** 拒答重路由 Binding 补偿的最大尝试次数，包含首次调用。 */
    private int bindingCompensationMaxAttempts = 2;
    /** 拒答重路由 Binding 补偿的重试间隔。 */
    private Duration bindingCompensationRetryBackoff = Duration.ofMillis(50);
    /** 是否接收 DomainAgent 后台异步任务协议。 */
    private boolean asyncTaskEnabled = false;
    /** DomainAgent 后台异步任务最长等待时间。 */
    private Duration asyncTaskMaxDuration = Duration.ofHours(24);
    /** 单次异步结果回调允许的最大 frame 数。 */
    private int asyncTaskCallbackMaxFrames = 512;
    /** 单次异步结果回调序列化后的最大 UTF-8 字节数。 */
    private int asyncTaskCallbackMaxBytes = 4 * 1024 * 1024;
    /** 单实例同时处理的异步结果回调数量。 */
    private int asyncTaskCallbackMaxConcurrency = 4;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getReferer() { return referer; }
    public void setReferer(String referer) { this.referer = referer; }
    public String getChatPath() { return chatPath; }
    public void setChatPath(String chatPath) { this.chatPath = chatPath; }
    public String getStopPath() { return stopPath; }
    public void setStopPath(String stopPath) { this.stopPath = stopPath; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public Duration getStreamIdleTimeout() { return streamIdleTimeout; }
    public void setStreamIdleTimeout(Duration streamIdleTimeout) { this.streamIdleTimeout = streamIdleTimeout; }
    public Duration getStreamTotalTimeout() { return streamTotalTimeout; }
    public void setStreamTotalTimeout(Duration streamTotalTimeout) { this.streamTotalTimeout = streamTotalTimeout; }
    public int getMaxAttachments() { return maxAttachments; }
    public void setMaxAttachments(int maxAttachments) { this.maxAttachments = maxAttachments; }
    public int getMaxPendingFrameBytes() { return maxPendingFrameBytes; }
    public void setMaxPendingFrameBytes(int maxPendingFrameBytes) { this.maxPendingFrameBytes = maxPendingFrameBytes; }
    @Deprecated(since = "1.0.0", forRemoval = false)
    public int getMaxFragmentBytes() { return maxFragmentBytes; }
    @Deprecated(since = "1.0.0", forRemoval = false)
    public void setMaxFragmentBytes(int maxFragmentBytes) { this.maxFragmentBytes = maxFragmentBytes; }
    public int getMaxReroutes() { return maxReroutes; }
    public void setMaxReroutes(int maxReroutes) { this.maxReroutes = maxReroutes; }
    public boolean isRefusalAutoSwitchEnabled() { return refusalAutoSwitchEnabled; }
    public void setRefusalAutoSwitchEnabled(boolean refusalAutoSwitchEnabled) {
        this.refusalAutoSwitchEnabled = refusalAutoSwitchEnabled;
    }
    public int getControlIoExecutorMaxSize() { return controlIoExecutorMaxSize; }
    public void setControlIoExecutorMaxSize(int controlIoExecutorMaxSize) {
        this.controlIoExecutorMaxSize = controlIoExecutorMaxSize;
    }
    public int getControlIoExecutorQueueCapacity() { return controlIoExecutorQueueCapacity; }
    public void setControlIoExecutorQueueCapacity(int controlIoExecutorQueueCapacity) {
        this.controlIoExecutorQueueCapacity = controlIoExecutorQueueCapacity;
    }
    public int getBindingCompensationMaxAttempts() { return bindingCompensationMaxAttempts; }
    public void setBindingCompensationMaxAttempts(int bindingCompensationMaxAttempts) {
        this.bindingCompensationMaxAttempts = bindingCompensationMaxAttempts;
    }
    public Duration getBindingCompensationRetryBackoff() { return bindingCompensationRetryBackoff; }
    public void setBindingCompensationRetryBackoff(Duration bindingCompensationRetryBackoff) {
        this.bindingCompensationRetryBackoff = bindingCompensationRetryBackoff;
    }
    public boolean isAsyncTaskEnabled() { return asyncTaskEnabled; }
    public void setAsyncTaskEnabled(boolean asyncTaskEnabled) { this.asyncTaskEnabled = asyncTaskEnabled; }
    public Duration getAsyncTaskMaxDuration() { return asyncTaskMaxDuration; }
    public void setAsyncTaskMaxDuration(Duration asyncTaskMaxDuration) {
        this.asyncTaskMaxDuration = asyncTaskMaxDuration;
    }
    public int getAsyncTaskCallbackMaxFrames() { return asyncTaskCallbackMaxFrames; }
    public void setAsyncTaskCallbackMaxFrames(int asyncTaskCallbackMaxFrames) {
        this.asyncTaskCallbackMaxFrames = asyncTaskCallbackMaxFrames;
    }
    public int getAsyncTaskCallbackMaxBytes() { return asyncTaskCallbackMaxBytes; }
    public void setAsyncTaskCallbackMaxBytes(int asyncTaskCallbackMaxBytes) {
        this.asyncTaskCallbackMaxBytes = asyncTaskCallbackMaxBytes;
    }
    public int getAsyncTaskCallbackMaxConcurrency() { return asyncTaskCallbackMaxConcurrency; }
    public void setAsyncTaskCallbackMaxConcurrency(int asyncTaskCallbackMaxConcurrency) {
        this.asyncTaskCallbackMaxConcurrency = asyncTaskCallbackMaxConcurrency;
    }

    public int normalizedMaxAttachments() {
        return maxAttachments <= 0 ? 10 : maxAttachments;
    }

    public String normalizedReferer() {
        String configured = referer == null ? "" : referer.trim();
        if (!configured.isBlank()) {
            return configured;
        }
        return baseUrl == null ? "" : baseUrl.trim();
    }

    public int normalizedMaxPendingFrameBytes() {
        return maxPendingFrameBytes <= 0 ? DEFAULT_MAX_PENDING_FRAME_BYTES : maxPendingFrameBytes;
    }

    @Deprecated(since = "1.0.0", forRemoval = false)
    public int normalizedMaxFragmentBytes() {
        return maxFragmentBytes <= 0 ? 8192 : maxFragmentBytes;
    }

    public int normalizedMaxReroutes() {
        return Math.max(0, Math.min(maxReroutes, 10));
    }

    public int normalizedControlIoExecutorMaxSize() {
        return Math.max(1, controlIoExecutorMaxSize);
    }

    public int normalizedControlIoExecutorQueueCapacity() {
        return Math.max(16, controlIoExecutorQueueCapacity);
    }

    public int normalizedBindingCompensationMaxAttempts() {
        return Math.max(1, Math.min(bindingCompensationMaxAttempts, 3));
    }

    public Duration normalizedBindingCompensationRetryBackoff() {
        if (bindingCompensationRetryBackoff == null || bindingCompensationRetryBackoff.isNegative()) {
            return Duration.ofMillis(50);
        }
        return bindingCompensationRetryBackoff.compareTo(Duration.ofSeconds(1)) > 0
                ? Duration.ofSeconds(1)
                : bindingCompensationRetryBackoff;
    }

    public Duration requiredAsyncTaskMaxDuration() {
        if (asyncTaskMaxDuration == null || asyncTaskMaxDuration.isZero() || asyncTaskMaxDuration.isNegative()) {
            throw new IllegalStateException("financeex.domain-agent.async-task-max-duration must be positive");
        }
        return asyncTaskMaxDuration;
    }

    public int requiredAsyncTaskCallbackMaxFrames() {
        if (asyncTaskCallbackMaxFrames <= 0) {
            throw new IllegalStateException("financeex.domain-agent.async-task-callback-max-frames must be positive");
        }
        return asyncTaskCallbackMaxFrames;
    }

    public int requiredAsyncTaskCallbackMaxBytes() {
        if (asyncTaskCallbackMaxBytes <= 0) {
            throw new IllegalStateException("financeex.domain-agent.async-task-callback-max-bytes must be positive");
        }
        return asyncTaskCallbackMaxBytes;
    }

    public int requiredAsyncTaskCallbackMaxConcurrency() {
        if (asyncTaskCallbackMaxConcurrency <= 0) {
            throw new IllegalStateException("financeex.domain-agent.async-task-callback-max-concurrency must be positive");
        }
        return asyncTaskCallbackMaxConcurrency;
    }

    @PostConstruct
    void validateAsyncTaskConfiguration() {
        requiredAsyncTaskMaxDuration();
        requiredAsyncTaskCallbackMaxFrames();
        requiredAsyncTaskCallbackMaxBytes();
        requiredAsyncTaskCallbackMaxConcurrency();
    }
}
