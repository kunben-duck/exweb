package com.huawei.it.ex.one.runtime.infrastructure.relay;

import com.huawei.it.ex.one.runtime.application.client.AgentRuntime;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.runtime.application.client.AgentRuntimeInteraction;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeRequest;
import com.huawei.it.ex.one.common.event.ChatEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * RelayAgent Runtime 防腐层实现。
 *
 * <p>该类是 application 层看到的唯一 Relay provider。它不直接拼接下游请求，
 * 统一委托给 Relay WebSocket 协议防腐层。</p>
 */
@Component
@ConditionalOnProperty(prefix = "financeex.agent-runtime.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RelayAgentRuntime implements AgentRuntime, AgentRuntimeInteraction {
    public static final String PROVIDER = "relay";

    private final RelayRuntimeProtocolAdapter protocolAdapter;

    public RelayAgentRuntime(RelayRuntimeProtocolAdapter protocolAdapter) {
        this.protocolAdapter = protocolAdapter;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        return protocolAdapter.query(request);
    }

    @Override
    public boolean supportsWaitingUserResponse(String runtimeProvider) {
        return PROVIDER.equalsIgnoreCase(runtimeProvider) && protocolAdapter.supportsUserResponseContinuation();
    }

    @Override
    public Flux<ChatEvent> continueWithUserResponse(AgentRuntimeInteractionResponseRequest request) {
        return protocolAdapter.continueWithUserResponse(request);
    }

    @Override
    public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
        return protocolAdapter.cancel(request);
    }
}
