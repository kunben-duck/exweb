package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteraction;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.domain.chat.ChatEvent;

import reactor.core.publisher.Flux;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AgentRuntime provider 注册表。
 *
 * <p>Runtime provider 可以同时注册多个，实际执行时由 RuntimeBinding.provider 或默认 fallback
 * provider 决定。这里是应用层唯一的 provider 选择点，避免主编排直接依赖具体 Runtime 实现。</p>
 */
@Service
public class AgentRuntimeRegistry {
    private final Map<String, AgentRuntime> runtimes;
    private final String defaultProvider;

    public AgentRuntimeRegistry(List<AgentRuntime> runtimes,
                                @Value("${financeex.agent-runtime.default-provider:relay}") String defaultProvider) {
        Map<String, AgentRuntime> indexed = new LinkedHashMap<>();
        if (runtimes != null) {
            for (AgentRuntime runtime : runtimes) {
                if (runtime != null && runtime.provider() != null && !runtime.provider().isBlank()) {
                    indexed.put(normalize(runtime.provider()), runtime);
                }
            }
        }
        this.runtimes = Map.copyOf(indexed);
        this.defaultProvider = normalize(defaultProvider == null || defaultProvider.isBlank() ? "relay" : defaultProvider);
    }

    public AgentRuntime defaultRuntime() {
        return runtime(defaultProvider);
    }

    public AgentRuntime runtime(String provider) {
        String key = normalize(provider == null || provider.isBlank() ? defaultProvider : provider);
        AgentRuntime runtime = runtimes.get(key);
        if (runtime == null) {
            throw new IllegalStateException("AgentRuntime provider 未注册: " + key);
        }
        return runtime;
    }

    public boolean supportsWaitingUserResponse(String provider) {
        AgentRuntime runtime = runtime(provider);
        return runtime instanceof AgentRuntimeInteraction interaction
                && interaction.supportsWaitingUserResponse(runtime.provider());
    }

    public Flux<ChatEvent> continueWithUserResponse(AgentRuntimeInteractionResponseRequest request) {
        AgentRuntime runtime = runtime(request == null ? null : request.provider());
        if (runtime instanceof AgentRuntimeInteraction interaction
                && interaction.supportsWaitingUserResponse(runtime.provider())) {
            return interaction.continueWithUserResponse(request);
        }
        return Flux.error(new UnsupportedOperationException("AgentRuntime provider 不支持交互续接: "
                + (runtime == null ? "" : runtime.provider())));
    }

    public String defaultProvider() {
        return defaultProvider;
    }

    private String normalize(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }
}
