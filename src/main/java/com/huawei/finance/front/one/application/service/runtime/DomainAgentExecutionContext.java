package com.huawei.finance.front.one.application.service.runtime;

import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;

/**
 * DomainAgent 调用执行上下文。
 */
public record DomainAgentExecutionContext(
        ChatCommand command,
        String runId,
        RouteTarget route,
        UserContext user,
        RuntimeBinding binding,
        RuntimeForwardHeaders forwardHeaders
) {
}
