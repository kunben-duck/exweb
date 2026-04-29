package com.huawei.finance.front.one.application.gateway;

import com.huawei.finance.front.one.domain.agent.AgentRuntimeProvider;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import reactor.core.publisher.Flux;

/**
 * 复杂任务统一 AgentRuntime 抽象。
 *
 * <p>RelayAgent、AgentScope、Spring AI、LangChain 等实现都必须以 provider 形式接入该接口。
 * v2 只强制 query 能力，其他 lifecycle 能力例如 cancel/status/compact 后续可作为独立 port 扩展。</p>
 */
public interface AgentRuntime {
    AgentRuntimeProvider provider();
    boolean supports(AgentRuntimeProvider provider);
    Flux<ChatEvent> query(AgentRuntimeRequest request);
}
