package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

/**
 * DomainAgent 调用执行上下文。
 */
public record DomainAgentExecutionContext(
        ChatCommand command,
        String runId,
        RouteTarget route,
        UserContext user,
        RuntimeBinding binding,
        RuntimeForwardHeaders forwardHeaders,
        String userMessageId
) {
    public DomainAgentExecutionContext(ChatCommand command, String runId, RouteTarget route,
                                       UserContext user, RuntimeBinding binding,
                                       RuntimeForwardHeaders forwardHeaders) {
        this(command, runId, route, user, binding, forwardHeaders, null);
    }
}
