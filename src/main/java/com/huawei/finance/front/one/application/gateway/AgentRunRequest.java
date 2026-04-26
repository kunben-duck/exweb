package com.huawei.finance.front.one.application.gateway;

import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatResponseMode;
import com.huawei.finance.front.one.domain.chat.ImMessageType;
import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import java.util.List;
import java.util.Map;

public record AgentRunRequest(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String message,
        ImMessageType messageType,
        ChatResponseMode responseMode,
        List<AttachmentRef> attachments,
        MemoryContext memoryContext,
        IntentDecision intentDecision,
        List<ToolDefinition> availableTools,
        Map<String, Object> variables,
        Map<String, Object> metadata
) {}
