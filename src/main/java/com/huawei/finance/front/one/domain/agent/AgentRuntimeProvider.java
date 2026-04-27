package com.huawei.finance.front.one.domain.agent;

import java.util.Arrays;

/**
 * AgentRuntime provider 类型。
 */
public enum AgentRuntimeProvider {
    RELAY_AGENT("relay-agent"),
    AGENTSCOPE("agentscope"),
    SPRING_AI("spring-ai"),
    LANGCHAIN("langchain");

    private final String configValue;

    AgentRuntimeProvider(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static AgentRuntimeProvider fromConfigValue(String value) {
        String normalized = value == null ? "" : value.trim();
        return Arrays.stream(values())
                .filter(provider -> provider.configValue.equalsIgnoreCase(normalized) || provider.name().equalsIgnoreCase(normalized.replace('-', '_')))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported AgentRuntime provider: " + value));
    }
}
