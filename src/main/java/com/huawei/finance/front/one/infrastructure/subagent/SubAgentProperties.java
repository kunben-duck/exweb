package com.huawei.finance.front.one.infrastructure.subagent;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 第三方 SubAgent 调用配置。
 *
 * <p>配置项只描述 SubAgent 业务编码和 HTTP 地址，不把具体中台业务响应结构写入主流程。
 * 当前正式版本 SubAgent 仅支持单轮 HTTP 文本流调用。</p>
 */
@ConfigurationProperties(prefix = "financeex.sub-agent")
public class SubAgentProperties {
    /** 调用第三方 SubAgent 的超时时间。 */
    private Duration timeout = Duration.ofSeconds(30);
    /** SubAgent 编码到调用配置的映射。 */
    private Map<String, AgentEndpoint> agents = new HashMap<>();

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
        /** SubAgent HTTP 流式接口完整地址。 */
        private String endpoint;
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

    }
}
