package com.huawei.it.ex.one.domain.memory;

import com.huawei.it.ex.one.domain.chat.ChatMessage;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * SuperAgent 单轮运行的可选记忆上下文快照。
 *
 * <p>当短期和长期记忆均关闭时，该对象应为空上下文。下游 RouteSignal、DomainAgent 和 AgentRuntime
 * 必须把空上下文视为合法输入，不能因为没有记忆增强而阻断主链路。</p>
 *
 * @param recentMessages 短期记忆装配的最近问答消息列表。
 * @param longTermMemories 长期记忆服务返回的相关记忆条目。
 */
public record MemoryContext(
        List<ChatMessage> recentMessages,
        List<LongTermMemoryItem> longTermMemories,
        RouteMemoryContext routeMemory,
        @JsonIgnore boolean shortTermEnabled,
        @JsonIgnore List<ConversationMemoryMessage> agentRuntimeMessages
) {
    public MemoryContext {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        longTermMemories = longTermMemories == null ? List.of() : List.copyOf(longTermMemories);
        routeMemory = routeMemory == null ? RouteMemoryContext.empty() : routeMemory;
        agentRuntimeMessages = agentRuntimeMessages == null ? List.of() : List.copyOf(agentRuntimeMessages);
    }

    public MemoryContext(List<ChatMessage> recentMessages, List<LongTermMemoryItem> longTermMemories,
                         RouteMemoryContext routeMemory) {
        this(recentMessages, longTermMemories, routeMemory, false, List.of());
    }

    public MemoryContext(List<ChatMessage> recentMessages, List<LongTermMemoryItem> longTermMemories) {
        this(recentMessages, longTermMemories, RouteMemoryContext.empty());
    }

    /**
     * 创建空记忆上下文。
     *
     * @return 不包含短期消息和长期记忆的上下文快照。
     */
    public static MemoryContext empty() {
        return new MemoryContext(List.of(), List.of(), RouteMemoryContext.empty(), false, List.of());
    }

    /**
     * @param routeMemory 本轮路由上下文。
     * @return 替换 RouteMemory 后的新快照。
     */
    public MemoryContext withRouteMemory(RouteMemoryContext routeMemory) {
        return new MemoryContext(recentMessages, longTermMemories, routeMemory,
                shortTermEnabled, agentRuntimeMessages);
    }

    /**
     * @return 当前上下文是否不包含任何记忆增强数据。
     */
    public boolean isEmpty() {
        return recentMessages.isEmpty() && agentRuntimeMessages.isEmpty() && longTermMemories.isEmpty()
                && (routeMemory == null || !routeMemory.hasHistory());
    }
}
