package com.huawei.finance.front.one.application.integration.intent;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.memory.MemoryContext;

/**
 * IntentAgent 单次路由请求。
 */
public record IntentAgentRouteRequest(
        UserContext user,
        ChatSession session,
        ChatCommand command,
        MemoryContext memory,
        String runId,
        String routeTrigger
) {
}
