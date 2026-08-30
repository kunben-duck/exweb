/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 财经领域 DomainAgent 出站调用防腐层。
 *
 * <p>该端口只服务前端显式选择 domainAgentId 的领域 Agent 调用场景。它不代表默认复杂任务 Runtime，
 * 也不创建 RuntimeBinding；下游私有 chat/upload 协议必须在实现层转换为 ChatService 标准事件。</p>
 */
public interface DomainAgentClient {
    /**
     * 调用 DomainAgent chat 流式接口。
     *
     * @param request DomainAgent 指定调用请求。
     * @return ChatService 标准事件流。
     */
    Flux<ChatEvent> query(DomainAgentRequest request);

    /**
     * 尽力取消 DomainAgent run。
     *
     * @param request 取消请求。
     * @return 完成信号。
     */
    Mono<Void> cancel(DomainAgentCancelRequest request);
}
