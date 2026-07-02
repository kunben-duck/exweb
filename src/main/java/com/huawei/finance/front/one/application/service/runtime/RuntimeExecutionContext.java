package com.huawei.finance.front.one.application.service.runtime;

import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;

/**
 * AgentRuntime 执行上下文。
 *
 * <p>该对象是 ChatService 到 Runtime 防腐层的应用级输入，不是 Relay wire 请求体。
 * Cookie 等转发头只保存在 {@link RuntimeForwardHeaders} 内存快照中，由 adapter 决定是否透传，
 * 不得写入事件、run metadata 或日志。</p>
 */
public record RuntimeExecutionContext(
        ChatCommand command,
        String runId,
        MemoryContext memory,
        IntentDecision intent,
        RouteTarget route,
        UserContext user,
        RuntimeBinding binding,
        RuntimeSessionMode runtimeSessionMode,
        RuntimeForwardHeaders forwardHeaders
) {
}
