package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeInteractionResponseRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeInteraction;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * RelayAgent Runtime 防腐层实现。
 *
 * <p>该类是 application 层看到的唯一 Relay provider。它不直接拼接下游请求，
 * 只根据配置选择一个 {@link RelayRuntimeProtocolAdapter}。若未来新增其他 Relay 协议，
 * 实现新的 adapter 即可，不需要污染主编排。</p>
 */
@Component
@EnableConfigurationProperties(RelayAgentProperties.class)
@ConditionalOnProperty(prefix = "financeex.agent-runtime.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RelayAgentRuntime implements AgentRuntime, AgentRuntimeInteraction {
    public static final String PROVIDER = "relay";
    static final String STREAM_HTTP_ADAPTER = "relay-stream-http";

    private final Map<String, RelayRuntimeProtocolAdapter> adapters;
    private final RelayRuntimeProtocolAdapter selectedAdapter;

    public RelayAgentRuntime(List<RelayRuntimeProtocolAdapter> adapters, RelayAgentProperties properties) {
        this.adapters = indexAdapters(adapters);
        this.selectedAdapter = requireConfiguredAdapter(properties);
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        return selectedAdapter.query(request);
    }

    @Override
    public boolean supportsWaitingUserResponse(String runtimeProvider) {
        return PROVIDER.equalsIgnoreCase(runtimeProvider) && selectedAdapter.supportsUserResponseContinuation();
    }

    @Override
    public Flux<ChatEvent> continueWithUserResponse(AgentRuntimeInteractionResponseRequest request) {
        return selectedAdapter.continueWithUserResponse(request);
    }

    @Override
    public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
        return selectedAdapter.cancel(request);
    }

    private Map<String, RelayRuntimeProtocolAdapter> indexAdapters(List<RelayRuntimeProtocolAdapter> adapters) {
        Map<String, RelayRuntimeProtocolAdapter> indexed = new LinkedHashMap<>();
        for (RelayRuntimeProtocolAdapter adapter : adapters) {
            for (String name : adapter.adapterNames()) {
                if (name != null && !name.isBlank()) {
                    indexed.put(name.trim().toLowerCase(), adapter);
                }
            }
        }
        return Map.copyOf(indexed);
    }

    private RelayRuntimeProtocolAdapter requireConfiguredAdapter(RelayAgentProperties properties) {
        String configured = properties == null || properties.getRelay() == null
                ? STREAM_HTTP_ADAPTER
                : properties.getRelay().getAdapter();
        String adapterName = normalize(configured);
        RelayRuntimeProtocolAdapter adapter = adapters.get(adapterName);
        if (adapter == null) {
            throw new IllegalStateException("Relay adapter '" + adapterName
                    + "' is not registered. Registered adapters: " + adapters.keySet());
        }
        return adapter;
    }

    private String normalize(String value) {
        return value == null || value.isBlank()
                ? STREAM_HTTP_ADAPTER
                : value.trim().toLowerCase(Locale.ROOT);
    }
}
