package com.huawei.finance.front.one.domain.memory;

import com.huawei.finance.front.one.domain.chat.ChatMessage;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record MemoryContext(
        List<ChatMessage> recentMessages,
        Optional<ConversationSummary> conversationSummary,
        Map<String, Object> workingVariables,
        List<LongTermMemoryItem> longTermMemories
) {}
