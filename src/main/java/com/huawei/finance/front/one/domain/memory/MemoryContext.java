package com.huawei.finance.front.one.domain.memory;

import com.huawei.finance.front.one.domain.chat.ChatMessage;
import java.util.List;

/**
 * SuperAgent 单轮运行的可选记忆上下文快照。
 *
 * <p>当短期和长期记忆均关闭时，该对象应为空上下文。下游 RouteSignal、SubAgent 和 AgentRuntime
 * 必须把空上下文视为合法输入，不能因为没有记忆增强而阻断主链路。</p>
 *
 * @param recentMessages 短期记忆装配的最近问答消息列表。
 * @param longTermMemories 长期记忆服务返回的相关记忆条目。
 */
public record MemoryContext(
        List<ChatMessage> recentMessages,
        List<LongTermMemoryItem> longTermMemories
) {
    public MemoryContext {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        longTermMemories = longTermMemories == null ? List.of() : List.copyOf(longTermMemories);
    }

    /**
     * 创建空记忆上下文。
     *
     * @return 不包含短期消息和长期记忆的上下文快照。
     */
    public static MemoryContext empty() {
        return new MemoryContext(List.of(), List.of());
    }

    /**
     * @return 当前上下文是否不包含任何记忆增强数据。
     */
    public boolean isEmpty() {
        return recentMessages.isEmpty() && longTermMemories.isEmpty();
    }
}
