package com.huawei.finance.front.one.application.integration.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.huawei.finance.front.one.domain.auth.UserContext;
import java.util.Map;

/**
 * 老 Agent 指定技能取消请求。
 *
 * @param user 当前用户身份快照。
 * @param sessionId ChatService 会话 ID。
 * @param runId ChatService run ID。
 * @param skillId 本轮指定技能 ID。
 * @param reason 取消原因。
 * @param metadata 扩展诊断字段。
 * @param forwardHeaders stop 入口捕获的 Cookie 等转发头快照；仅用于老 Agent stop 请求头。
 */
public record LegacySkillCancelRequest(
        UserContext user,
        String sessionId,
        String runId,
        String skillId,
        String reason,
        Map<String, Object> metadata,
        @JsonIgnore RuntimeForwardHeaders forwardHeaders
) {
    public LegacySkillCancelRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }
}
