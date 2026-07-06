package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeHitlResponseRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.Set;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Relay Runtime 下游 API 协议 adapter。
 *
 * <p>该接口是 Relay provider 内部的二级防腐层。{@link RelayAgentRuntime} 只根据配置选择一个
 * adapter 并委托执行；每个 adapter 独立负责自己的请求体构造、鉴权、响应解析、流式事件转换和下游
 * 取消语义。这样新增新的 Relay HTTP 变体或其他企业协议时，不需要改动
 * FinanceEXChatService 主编排。当前内置 {@code relay-stream-http} 与 {@code relay-websocket}
 * 两种实现，生产默认仍使用 stream-http。</p>
 */
public interface RelayRuntimeProtocolAdapter {
    /**
     * @return 当前 adapter 支持的配置名称集合，例如 {@code relay-stream-http}。
     */
    Set<String> adapterNames();

    /**
     * 调用下游 Runtime API，并转换成标准 ChatEvent 流。
     *
     * @param request SuperAgent 标准 Runtime 请求。
     * @return 标准聊天事件流。
     */
    Flux<ChatEvent> query(AgentRuntimeRequest request);

    /**
     * @return true 表示该 adapter 支持协议级等待用户输入后的续接。
     */
    default boolean supportsUserResponseContinuation() {
        return false;
    }

    /**
     * 继续执行协议级等待用户输入的 Runtime 会话。
     *
     * @param request HITL 续接请求。
     * @return 标准聊天事件流。
     */
    default Flux<ChatEvent> continueWithUserResponse(AgentRuntimeHitlResponseRequest request) {
        return Flux.error(new UnsupportedOperationException("Relay adapter 不支持 HITL 续接"));
    }

    /**
     * 尽力取消下游 Runtime run。
     *
     * @param request Runtime 取消请求。
     * @return 完成信号；下游不支持取消时返回空 Mono。
     */
    Mono<Void> cancel(AgentRuntimeCancelRequest request);
}
