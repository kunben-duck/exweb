package com.huawei.finance.front.one.domain.memory;

import com.huawei.finance.front.one.domain.chat.ChatMessage;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SuperAgent 单轮运行上下文快照。
 *
 * @param recentMessages 当前会话最近消息列表。
 * @param conversationSummary 会话历史摘要，可为空。
 * @param workingVariables 当前会话工作记忆变量。
 * @param longTermMemories 外部长记忆服务返回的长期记忆条目。
 */
public record MemoryContext(
        List<ChatMessage> recentMessages,
        Optional<ConversationSummary> conversationSummary,
        Map<String, Object> workingVariables,
        List<LongTermMemoryItem> longTermMemories
) {}
