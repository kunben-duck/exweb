package com.huawei.finance.front.one.infrastructure.subagent;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 第三方 SubAgent 调用配置。
 *
 * <p>配置项只描述协议、地址和交互模式，不把具体中台业务响应结构写入主流程。自然语言 Agent
 * 默认通过契约 Prompt 约束输出；私有协议差异应收敛在 adapter 层。</p>
 */
@ConfigurationProperties(prefix = "financeex.sub-agent")
public class SubAgentProperties {
    /** 缺少 endpoint 时是否允许本地 mock 响应，生产环境建议关闭。 */
    private boolean mockFallbackEnabled = true;
    /** 调用第三方 SubAgent 的超时时间。 */
    private Duration timeout = Duration.ofSeconds(30);
    /** SubAgent 编码到调用配置的映射。 */
    private Map<String, AgentEndpoint> agents = new HashMap<>();

    public boolean isMockFallbackEnabled() {
        return mockFallbackEnabled;
    }

    public void setMockFallbackEnabled(boolean mockFallbackEnabled) {
        this.mockFallbackEnabled = mockFallbackEnabled;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Map<String, AgentEndpoint> getAgents() {
        return agents;
    }

    public void setAgents(Map<String, AgentEndpoint> agents) {
        this.agents = agents;
    }

    public static class AgentEndpoint {
        /** 该 SubAgent 是否启用。 */
        private boolean enabled = true;
        /** SubAgent 调用协议，首版支持 http，后续可扩展 a2a。 */
        private String protocol = "http";
        /** SubAgent 服务 endpoint。 */
        private String endpoint;
        /** 交互模式：natural-language-contract、raw-text 或 custom-adapter。 */
        private String interactionMode = "raw-text";
        /** 任务目标，用于自然语言契约 Prompt。 */
        private String taskGoal;
        /** 任务领域，用于 TaskCard 和 Prompt 诊断。 */
        private String taskDomain;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getInteractionMode() {
            return interactionMode;
        }

        public void setInteractionMode(String interactionMode) {
            this.interactionMode = interactionMode;
        }

        public String getTaskGoal() {
            return taskGoal;
        }

        public void setTaskGoal(String taskGoal) {
            this.taskGoal = taskGoal;
        }

        public String getTaskDomain() {
            return taskDomain;
        }

        public void setTaskDomain(String taskDomain) {
            this.taskDomain = taskDomain;
        }
    }
}
