package com.huawei.finance.front.one.domain.agent;

import java.util.Arrays;

/**
 * AgentRuntime provider 类型。
 */
public enum AgentRuntimeProvider {
    /** 远程 RelayAgent 服务实现。 */
    RELAY_AGENT("relay-agent"),
    /** 本服务进程内 AgentScope 实现。 */
    AGENTSCOPE("agentscope");

    /** 配置文件中使用的 provider 编码。 */
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
