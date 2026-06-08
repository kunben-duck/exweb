package com.huawei.finance.front.one.application.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 老 Agent 指定技能接入配置。
 *
 * <p>该配置只用于前端显式选择 skillId 的历史兼容路径。默认关闭，避免未配置老 Agent 地址时
 * 误把普通聊天流量路由到历史服务。</p>
 */
@ConfigurationProperties(prefix = "financeex.legacy-skill")
public class LegacySkillProperties {
    /** 是否启用历史技能调用能力。 */
    private boolean enabled = false;
    /** 老 Agent 服务基础地址。 */
    private String baseUrl = "";
    /** 老 Agent chat 流式接口路径。 */
    private String chatPath = "/api/chat";
    /** 老 Agent stop 接口路径；为空表示不支持下游取消。 */
    private String stopPath = "";
    /** 老 Agent 调用超时时间。 */
    private Duration timeout = Duration.ofSeconds(120);
    /** 允许调用的 skillId；为空表示不额外限制。 */
    private List<String> allowedSkillIds = new ArrayList<>();
    /** 老 Agent 默认平台字段。 */
    private String defaultPlatform = "PC";
    /** 老 Agent 默认 queryType。 */
    private String defaultQueryType = "normalQa";
    /** 老 Agent 默认 stream flag，保留老接口 steamFlag 拼写。 */
    private String defaultStreamFlag = "stream";
    /** 老 Agent 默认思考开关。 */
    private int defaultIsThink = 1;
    /** 单次指定技能调用最大附件数。 */
    private int maxAttachments = 10;

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
    public List<String> getAllowedSkillIds() { return allowedSkillIds; }
    public void setAllowedSkillIds(List<String> allowedSkillIds) {
        this.allowedSkillIds = allowedSkillIds == null ? List.of() : allowedSkillIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }
    public String getDefaultPlatform() { return defaultPlatform; }
    public void setDefaultPlatform(String defaultPlatform) { this.defaultPlatform = defaultPlatform; }
    public String getDefaultQueryType() { return defaultQueryType; }
    public void setDefaultQueryType(String defaultQueryType) { this.defaultQueryType = defaultQueryType; }
    public String getDefaultStreamFlag() { return defaultStreamFlag; }
    public void setDefaultStreamFlag(String defaultStreamFlag) { this.defaultStreamFlag = defaultStreamFlag; }
    public int getDefaultIsThink() { return defaultIsThink; }
    public void setDefaultIsThink(int defaultIsThink) { this.defaultIsThink = defaultIsThink; }
    public int getMaxAttachments() { return maxAttachments; }
    public void setMaxAttachments(int maxAttachments) { this.maxAttachments = maxAttachments; }

    public boolean skillAllowed(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return false;
        }
        return allowedSkillIds == null || allowedSkillIds.isEmpty() || allowedSkillIds.contains(skillId);
    }

    public int normalizedMaxAttachments() {
        return maxAttachments <= 0 ? 10 : maxAttachments;
    }
}
