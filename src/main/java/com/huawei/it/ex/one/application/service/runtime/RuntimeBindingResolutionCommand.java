package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;

/** 解析本轮 Relay RuntimeBinding 所需的不可变输入。 */
public record RuntimeBindingResolutionCommand(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String leafMessageId,
        AgentModeProfile agentMode
) {
}
