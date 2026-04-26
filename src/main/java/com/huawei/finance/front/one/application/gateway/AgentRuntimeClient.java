package com.huawei.finance.front.one.application.gateway;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.routing.RuntimeProtocol;
import reactor.core.publisher.Flux;

public interface AgentRuntimeClient {
    RuntimeProtocol protocol();
    boolean supports(RuntimeProtocol protocol);
    Flux<ChatEvent> stream(RuntimeRequest request);
}
