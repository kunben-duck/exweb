package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * RelayAgent Runtime 防腐层实现。
 *
 * <p>该类是 application 层看到的唯一 Relay provider。它不直接拼接 HTTP/WebSocket 请求，
 * 只根据 {@code financeex.agent-runtime.api-adapter} 选择具体的
 * {@link RelayRuntimeProtocolAdapter}。真实 Relay stream-http、Relay WebSocket、DeepSeek
 * 替身等协议差异都被收敛到 adapter 内部。</p>
 */
@Component
@EnableConfigurationProperties(RelayAgentProperties.class)
@ConditionalOnExpression("'${financeex.agent-runtime.provider:relay}' == 'relay'")
public class RelayAgentRuntime implements AgentRuntime {
    private final RelayAgentProperties properties;
    private final Map<String, RelayRuntimeProtocolAdapter> adapters;

    public RelayAgentRuntime(RelayAgentProperties properties, List<RelayRuntimeProtocolAdapter> adapters) {
        this.properties = properties;
        this.adapters = indexAdapters(adapters);
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        return selectedAdapter().query(request);
    }

    @Override
    public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
        return selectedAdapter().cancel(request);
    }

    private RelayRuntimeProtocolAdapter selectedAdapter() {
        String adapterName = properties.selectedApiAdapter();
        RelayRuntimeProtocolAdapter adapter = adapters.get(adapterName);
        if (adapter == null) {
            throw new RelayRuntimeProtocolException("Unsupported Relay api-adapter: " + adapterName);
        }
        return adapter;
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
}
