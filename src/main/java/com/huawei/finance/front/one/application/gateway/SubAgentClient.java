package com.huawei.finance.front.one.application.gateway;

import com.huawei.finance.front.one.domain.agent.AgentQueryRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import reactor.core.publisher.Flux;

public interface SubAgentClient {
    Flux<ChatEvent> query(AgentQueryRequest request);
}
