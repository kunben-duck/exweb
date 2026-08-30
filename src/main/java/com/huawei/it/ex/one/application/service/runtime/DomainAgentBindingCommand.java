/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;

import java.util.Map;

/**
 * DomainAgent 会话绑定命令。
 *
 * <p>DomainAgent 和 Relay 一样拥有自己的上下文。每次显式选择、意图命中或确认切换时，
 * ChatService 都通过该命令创建新的会话级绑定并取消旧绑定。</p>
 */
public record DomainAgentBindingCommand(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String leafMessageId,
        String domainAgentId,
        String routeSource,
        Map<String, Object> intentMetadata,
        AgentModeProfile agentMode
) {
    public DomainAgentBindingCommand {
        intentMetadata = intentMetadata == null ? Map.of() : Map.copyOf(intentMetadata);
    }

    public DomainAgentBindingCommand(
            String tenantId, String userId, String sessionId, String runId, String leafMessageId,
            String domainAgentId, String routeSource, Map<String, Object> intentMetadata) {
        this(tenantId, userId, sessionId, runId, leafMessageId, domainAgentId, routeSource, intentMetadata, null);
    }
}
