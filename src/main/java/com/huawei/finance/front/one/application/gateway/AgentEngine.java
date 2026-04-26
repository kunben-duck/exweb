package com.huawei.finance.front.one.application.gateway;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import reactor.core.publisher.Flux;

public interface AgentEngine {
    AgentEngineType engineType();
    boolean supports(AgentEngineType engineType);
    Flux<ChatEvent> run(AgentRunRequest request);
}
