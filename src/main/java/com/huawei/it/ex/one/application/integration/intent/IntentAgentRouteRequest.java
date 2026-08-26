package com.huawei.it.ex.one.application.integration.intent;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

/**
 * IntentAgent 单次路由请求。
 */
public record IntentAgentRouteRequest(
        UserContext user,
        ChatSession session,
        ChatCommand command,
        MemoryContext memory,
        String runId,
        String routeTrigger,
        String userMessageId
) {
    public IntentAgentRouteRequest(UserContext user,
                                   ChatSession session,
                                   ChatCommand command,
                                   MemoryContext memory,
                                   String runId,
                                   String routeTrigger) {
        this(user, session, command, memory, runId, routeTrigger, null);
    }
}
