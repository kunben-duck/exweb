package com.huawei.finance.front.one.application.service.runtime;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;

/**
 * 一次性 SubAgent 执行上下文。
 *
 * <p>SubAgent 用于用例库或意图服务高置信命中的一次性任务；上下文只携带本轮消息、可选记忆和
 * 路由信号，不允许 SubAgent 直接读写 ChatService 的会话事实源。</p>
 */
public record SubAgentExecutionContext(
        ChatCommand command,
        String runId,
        MemoryContext memory,
        RouteTarget route,
        UserContext user
) {
}
