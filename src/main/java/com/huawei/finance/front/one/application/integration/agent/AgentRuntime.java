package com.huawei.finance.front.one.application.integration.agent;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 复杂任务 AgentRuntime 防腐层。
 *
 * <p>SuperAgent 主控服务只依赖这个应用层端口提交复杂任务查询，不直接依赖任何具体 Runtime
 * 的 SDK、HTTP 协议或内部会话模型。当前上线版本默认装配 Relay Runtime adapter。
 * {@code financeex.agent-runtime.provider} 表示 Runtime 类型。当前 Relay provider 固定使用
 * streamable HTTP；后续新增协议时应新增 provider 内部 adapter，而不是改动应用层端口。</p>
 */
public interface AgentRuntime {
    /**
     * 向当前装配的 AgentRuntime 提交一轮查询。
     *
     * @param request Runtime 查询请求。
     * @return 标准聊天事件流。
     */
    Flux<ChatEvent> query(AgentRuntimeRequest request);

    /**
     * 尽力取消已提交到 Runtime 的 run。
     *
     * <p>取消失败不得影响本服务本地 run.cancelled 事件；实现方应把错误吞掉或转成空 Mono。</p>
     *
     * @param request Runtime 取消请求。
     * @return 完成信号。
     */
    Mono<Void> cancel(AgentRuntimeCancelRequest request);
}
