package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * RelayAgent Runtime 防腐层实现。
 *
 * <p>该类是 application 层看到的唯一 Relay provider。它不直接拼接下游请求，
 * 固定委托 {@code relay-stream-http} {@link RelayRuntimeProtocolAdapter}。当前上线版本不再
 * 暴露下游协议选择配置；若未来新增其他 Relay 协议，实现新的 adapter 即可，不需要污染主编排。</p>
 */
@Component
@ConditionalOnExpression("'${financeex.agent-runtime.provider:relay}' == 'relay'")
public class RelayAgentRuntime implements AgentRuntime {
    static final String STREAM_HTTP_ADAPTER = "relay-stream-http";

    private final Map<String, RelayRuntimeProtocolAdapter> adapters;
    private final RelayRuntimeProtocolAdapter streamHttpAdapter;

    public RelayAgentRuntime(List<RelayRuntimeProtocolAdapter> adapters) {
        this.adapters = indexAdapters(adapters);
        this.streamHttpAdapter = requireStreamHttpAdapter();
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        return streamHttpAdapter.query(request);
    }

    @Override
    public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
        return streamHttpAdapter.cancel(request);
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

    private RelayRuntimeProtocolAdapter requireStreamHttpAdapter() {
        RelayRuntimeProtocolAdapter adapter = adapters.get(STREAM_HTTP_ADAPTER);
        if (adapter == null) {
            throw new IllegalStateException("Relay stream-http adapter is required. Registered adapters: "
                    + adapters.keySet());
        }
        return adapter;
    }
}
