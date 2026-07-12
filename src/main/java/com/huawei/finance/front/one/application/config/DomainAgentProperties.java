package com.huawei.finance.front.one.application.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 财经领域 DomainAgent 指定调用配置。
 *
 * <p>该配置只用于前端显式选择 domainAgentId 的领域 Agent 调用路径。默认关闭，避免未配置
 * DomainAgent 服务地址时误把普通聊天流量路由到指定领域 Agent。</p>
 */
@ConfigurationProperties(prefix = "financeex.domain-agent")
public class DomainAgentProperties {
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
    /** DomainAgent 调用超时时间。 */
    private Duration timeout = Duration.ofSeconds(120);
    /** 单次 DomainAgent 调用最大附件数。 */
    private int maxAttachments = 10;
    /** 单个未完成 DomainAgent 流式 frame 允许暂存的最大字节数，防止下游异常大 JSON 导致 OOM。 */
    private int maxPendingFrameBytes = 1024 * 1024;
    /** 大对象 fragment 输出的单片最大字节数，避免单个 WS/Event Resume 事件体过大。 */
    private int maxFragmentBytes = 8192;
    /** 同一 run 内拒答后最多重新路由次数。 */
    private int maxReroutes = 3;
    /** DomainAgent 控制事件数据库与缓存 IO 的隔离线程数。 */
    private int controlIoExecutorMaxSize = 2;
    /** DomainAgent 控制事件 IO 调度器队列容量。 */
    private int controlIoExecutorQueueCapacity = 128;

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
    public int getMaxAttachments() { return maxAttachments; }
    public void setMaxAttachments(int maxAttachments) { this.maxAttachments = maxAttachments; }
    public int getMaxPendingFrameBytes() { return maxPendingFrameBytes; }
    public void setMaxPendingFrameBytes(int maxPendingFrameBytes) { this.maxPendingFrameBytes = maxPendingFrameBytes; }
    public int getMaxFragmentBytes() { return maxFragmentBytes; }
    public void setMaxFragmentBytes(int maxFragmentBytes) { this.maxFragmentBytes = maxFragmentBytes; }
    public int getMaxReroutes() { return maxReroutes; }
    public void setMaxReroutes(int maxReroutes) { this.maxReroutes = maxReroutes; }
    public int getControlIoExecutorMaxSize() { return controlIoExecutorMaxSize; }
    public void setControlIoExecutorMaxSize(int controlIoExecutorMaxSize) {
        this.controlIoExecutorMaxSize = controlIoExecutorMaxSize;
    }
    public int getControlIoExecutorQueueCapacity() { return controlIoExecutorQueueCapacity; }
    public void setControlIoExecutorQueueCapacity(int controlIoExecutorQueueCapacity) {
        this.controlIoExecutorQueueCapacity = controlIoExecutorQueueCapacity;
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
        return maxPendingFrameBytes <= 0 ? 1024 * 1024 : maxPendingFrameBytes;
    }

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
}
