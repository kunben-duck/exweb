package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import com.huawei.finance.front.one.domain.agent.AgentRuntimeProvider;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 根据配置选择当前服务启用的 AgentRuntime provider。
 */
@Component
public class AgentRuntimeProviderSelector {
    private final List<AgentRuntime> runtimes;
    private final AgentRuntimeProvider configuredProvider;

    public AgentRuntimeProviderSelector(List<AgentRuntime> runtimes,
                                        @Value("${financeex.agent-runtime.provider:relay-agent}") String provider) {
        this.runtimes = runtimes;
        this.configuredProvider = AgentRuntimeProvider.fromConfigValue(provider);
    }

    public AgentRuntime select() {
        return runtimes.stream()
                .filter(runtime -> runtime.supports(configuredProvider))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No AgentRuntime provider for " + configuredProvider.configValue()));
    }

    public AgentRuntimeProvider configuredProvider() {
        return configuredProvider;
    }
}
