package com.huawei.finance.front.one.application.integration.agent;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import reactor.core.publisher.Flux;

/**
 * 复杂任务 AgentRuntime 防腐层。
 *
 * <p>SuperAgent 主控服务只依赖这个应用层端口提交复杂任务查询，不直接依赖任何具体 Runtime
 * 的 SDK、HTTP 协议或内部会话模型。当前上线版本默认装配 Relay Runtime adapter；后续替换
 * Runtime 实现时，应新增一个实现该接口的基础设施 adapter，并通过
 * {@code financeex.agent-runtime.provider} 切换装配。</p>
 */
public interface AgentRuntime {
    /**
     * 向当前装配的 AgentRuntime 提交一轮查询。
     *
     * @param request Runtime 查询请求。
     * @return 标准聊天事件流。
     */
    Flux<ChatEvent> query(AgentRuntimeRequest request);
}
