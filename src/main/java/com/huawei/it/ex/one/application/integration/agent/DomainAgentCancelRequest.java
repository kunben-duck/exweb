package com.huawei.it.ex.one.application.integration.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.huawei.it.ex.one.domain.auth.UserContext;
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
        @JsonIgnore RuntimeForwardHeaders forwardHeaders
) {
    public DomainAgentCancelRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }
}
