package com.huawei.finance.front.one.application.service.runtime;

import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.routing.RouteTarget;

/**
 * DomainAgent 指定调用执行上下文。
 *
 * <p>仅当前端通过 targetType/targetId 指定财经领域 Agent 时使用。该路径不创建
 * RuntimeBinding，也不参与默认 AgentRuntime 多轮续接。</p>
 */
public record DomainAgentExecutionContext(
        ChatCommand command,
        String runId,
        RouteTarget route,
        UserContext user,
        RuntimeForwardHeaders forwardHeaders
) {
}
