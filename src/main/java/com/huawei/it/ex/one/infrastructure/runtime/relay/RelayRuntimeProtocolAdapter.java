package com.huawei.it.ex.one.infrastructure.runtime.relay;

import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.domain.chat.ChatEvent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Relay Runtime WebSocket 协议防腐层。
 *
 * <p>该接口隔离 Relay WebSocket 请求体构造、鉴权、响应解析、流式事件转换和下游取消语义，
 * 避免协议细节进入 FinanceEXChatService 主编排。</p>
 */
public interface RelayRuntimeProtocolAdapter {
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
     * @param request Interaction 续接请求。
     * @return 标准聊天事件流。
     */
    default Flux<ChatEvent> continueWithUserResponse(AgentRuntimeInteractionResponseRequest request) {
        return Flux.error(new UnsupportedOperationException("Relay adapter 不支持 Interaction 续接"));
    }

    /**
     * 尽力取消下游 Runtime run。
     *
     * @param request Runtime 取消请求。
     * @return 完成信号；下游不支持取消时返回空 Mono。
     */
    Mono<Void> cancel(AgentRuntimeCancelRequest request);
}
