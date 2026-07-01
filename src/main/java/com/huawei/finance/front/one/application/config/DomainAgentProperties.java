package com.huawei.finance.front.one.application.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    /** DomainAgent chat 流式接口路径。 */
    private String chatPath = "/api/chat";
    /** DomainAgent stop 接口路径；为空表示不支持下游取消。 */
    private String stopPath = "";
    /** DomainAgent 调用超时时间。 */
    private Duration timeout = Duration.ofSeconds(120);
    /** 允许调用的 domainAgentId；为空表示不额外限制。 */
    private List<String> allowedDomainAgentIds = new ArrayList<>();
    /** DomainAgent 默认平台字段。 */
    private String defaultPlatform = "PC";
    /** DomainAgent 默认 qaType。 */
    private String defaultQaType = "normalQa";
    /** DomainAgent 默认 streamFlag。 */
    private String defaultStreamFlag = "stream";
    /** DomainAgent 默认思考开关。 */
    private int defaultIsThinking = 1;
    /** 单次 DomainAgent 调用最大附件数。 */
    private int maxAttachments = 10;
    /** 单个未完成 DomainAgent 流式 frame 允许暂存的最大字节数，防止下游异常大 JSON 导致 OOM。 */
    private int maxPendingFrameBytes = 1024 * 1024;
    /** 大对象 fragment 输出的单片最大字节数，避免单个 WS/Event Resume 事件体过大。 */
    private int maxFragmentBytes = 8192;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getChatPath() { return chatPath; }
    public void setChatPath(String chatPath) { this.chatPath = chatPath; }
    public String getStopPath() { return stopPath; }
    public void setStopPath(String stopPath) { this.stopPath = stopPath; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public List<String> getAllowedDomainAgentIds() { return allowedDomainAgentIds; }
    public void setAllowedDomainAgentIds(List<String> allowedDomainAgentIds) {
        this.allowedDomainAgentIds = allowedDomainAgentIds == null ? List.of() : allowedDomainAgentIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }
    public String getDefaultPlatform() { return defaultPlatform; }
    public void setDefaultPlatform(String defaultPlatform) { this.defaultPlatform = defaultPlatform; }
    public String getDefaultQaType() { return defaultQaType; }
    public void setDefaultQaType(String defaultQaType) { this.defaultQaType = defaultQaType; }
    public String getDefaultStreamFlag() { return defaultStreamFlag; }
    public void setDefaultStreamFlag(String defaultStreamFlag) { this.defaultStreamFlag = defaultStreamFlag; }
    public int getDefaultIsThinking() { return defaultIsThinking; }
    public void setDefaultIsThinking(int defaultIsThinking) { this.defaultIsThinking = defaultIsThinking; }
    public int getMaxAttachments() { return maxAttachments; }
    public void setMaxAttachments(int maxAttachments) { this.maxAttachments = maxAttachments; }
    public int getMaxPendingFrameBytes() { return maxPendingFrameBytes; }
    public void setMaxPendingFrameBytes(int maxPendingFrameBytes) { this.maxPendingFrameBytes = maxPendingFrameBytes; }
    public int getMaxFragmentBytes() { return maxFragmentBytes; }
    public void setMaxFragmentBytes(int maxFragmentBytes) { this.maxFragmentBytes = maxFragmentBytes; }

    public boolean domainAgentAllowed(String domainAgentId) {
        if (domainAgentId == null || domainAgentId.isBlank()) {
            return false;
        }
        return allowedDomainAgentIds == null || allowedDomainAgentIds.isEmpty()
                || allowedDomainAgentIds.contains(domainAgentId);
    }

    public int normalizedMaxAttachments() {
        return maxAttachments <= 0 ? 10 : maxAttachments;
    }

    public int normalizedMaxPendingFrameBytes() {
        return maxPendingFrameBytes <= 0 ? 1024 * 1024 : maxPendingFrameBytes;
    }

    public int normalizedMaxFragmentBytes() {
        return maxFragmentBytes <= 0 ? 8192 : maxFragmentBytes;
    }
}
