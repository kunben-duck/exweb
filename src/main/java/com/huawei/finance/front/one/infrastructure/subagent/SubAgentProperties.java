package com.huawei.finance.front.one.infrastructure.subagent;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "financeex.sub-agent")
public class SubAgentProperties {
    private boolean mockFallbackEnabled = true;
    private Duration timeout = Duration.ofSeconds(30);
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
        private boolean enabled = true;
        private String protocol = "http";
        private String endpoint;

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
    }
}
