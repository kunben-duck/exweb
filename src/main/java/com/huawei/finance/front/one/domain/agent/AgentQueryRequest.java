package com.huawei.finance.front.one.domain.agent;

import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatResponseMode;
import com.huawei.finance.front.one.domain.chat.ImMessageType;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import java.util.List;
import java.util.Map;

public record AgentQueryRequest(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String agentCode,
        String agentSessionId,
        String runtimeSessionId,
        String message,
        ImMessageType messageType,
        ChatResponseMode responseMode,
        List<AttachmentRef> attachments,
        MemoryContext memoryContext,
        RouteTarget routeTarget,
        Map<String, Object> metadata
) {
    public AgentQueryRequest {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
