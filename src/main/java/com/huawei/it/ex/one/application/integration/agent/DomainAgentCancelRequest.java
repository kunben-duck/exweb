/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.observation.TurnTraceContext;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;

/**
 * 财经领域 DomainAgent 指定调用取消请求。
 *
 * @param user 当前用户身份快照。
 * @param sessionId ChatService 会话 ID。
 * @param runId ChatService run ID。
 * @param domainAgentId 本轮指定 DomainAgent ID。
 * @param reason 取消原因。
 * @param metadata 扩展诊断字段。
 * @param forwardHeaders stop 入口捕获的 Cookie 等转发头快照；仅用于 DomainAgent stop 请求头。
 */
public record DomainAgentCancelRequest(
        UserContext user,
        String sessionId,
        String runId,
        String domainAgentId,
        String reason,
        Map<String, Object> metadata,
        @JsonIgnore RuntimeForwardHeaders forwardHeaders,
        @JsonIgnore TurnTraceContext turnTraceContext,
        @JsonIgnore String runtimeSessionId
) {
    /** 保留旧内部调用构造方式；新观测上下文不序列化为业务报文。 */
    public DomainAgentCancelRequest(
            UserContext user,
            String sessionId,
            String runId,
            String domainAgentId,
            String reason,
            Map<String, Object> metadata,
            RuntimeForwardHeaders forwardHeaders) {
        this(user, sessionId, runId, domainAgentId, reason, metadata, forwardHeaders, null, null);
    }

    public DomainAgentCancelRequest withTurnTraceContext(TurnTraceContext context) {
        return new DomainAgentCancelRequest(user, sessionId, runId, domainAgentId, reason, metadata, forwardHeaders, context, runtimeSessionId);
    }

    public DomainAgentCancelRequest withRuntimeSessionId(String value) {
        return new DomainAgentCancelRequest(user, sessionId, runId, domainAgentId, reason, metadata,
                forwardHeaders, turnTraceContext, value);
    }

    public DomainAgentCancelRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }
}
