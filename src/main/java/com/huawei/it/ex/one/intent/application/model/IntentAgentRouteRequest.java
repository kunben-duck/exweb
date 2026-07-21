package com.huawei.it.ex.one.intent.application.model;

import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;

/**
 * IntentAgent 单次路由请求。
 */
public record IntentAgentRouteRequest(
        UserContext user,
        IntentSessionSnapshot session,
        IntentCommandSnapshot command,
        MemoryContext memory,
        String runId,
        String routeTrigger
) {
}
