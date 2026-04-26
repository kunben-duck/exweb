package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.gateway.AgentEngine;
import com.huawei.finance.front.one.application.gateway.AgentEngineType;
import com.huawei.finance.front.one.application.gateway.AgentRunRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class AgentEngineRouter {
    private final List<AgentEngine> engines;
    private final AgentEngineType engineType;
    public AgentEngineRouter(List<AgentEngine> engines, @Value("${financeex.agent.engine:AGENTSCOPE}") AgentEngineType engineType) {
        this.engines = engines; this.engineType = engineType;
    }
    public Flux<ChatEvent> run(AgentRunRequest request) {
        return engines.stream().filter(e -> e.supports(engineType)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No agent engine for " + engineType))
                .run(request);
    }
}
