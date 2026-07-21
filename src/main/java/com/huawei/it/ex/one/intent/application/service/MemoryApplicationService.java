package com.huawei.it.ex.one.intent.application.service;

import com.huawei.it.ex.one.intent.application.config.MemoryProperties;
import com.huawei.it.ex.one.intent.application.model.IntentMemoryRequest;
import com.huawei.it.ex.one.intent.application.model.IntentLongTermMemorySnapshot;
import com.huawei.it.ex.one.intent.application.model.IntentMessageSnapshot;
import com.huawei.it.ex.one.intent.application.repository.LongTermMemoryStore;
import com.huawei.it.ex.one.intent.domain.memory.LongTermMemoryItem;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 可选 SuperAgent 记忆上下文装配服务。
 *
 * <p>短期记忆和长期记忆均由配置独立控制。全部关闭时，该服务只返回空上下文，不访问 Redis、
 * 数据库历史消息或长期记忆服务。会话压缩、长上下文窗口和 Runtime 内部记忆仍属于
 * AgentRuntime 自治能力。</p>
 */
@Service
public class MemoryApplicationService implements IntentMemoryService {
    private final IntentHistoryService messages;
    private final LongTermMemoryStore longTermMemory;
    private final MemoryProperties properties;

    public MemoryApplicationService(IntentHistoryService messages, LongTermMemoryStore longTermMemory,
                                    MemoryProperties properties) {
        this.messages = messages;
        this.longTermMemory = longTermMemory;
        this.properties = properties;
    }

    /**
     * 根据配置为本轮 run 装配可选记忆上下文。
     *
     * @param request 已由 Chat 上下文转换的意图记忆请求快照。
     * @return 记忆上下文；全部记忆关闭时返回空上下文。
     */
    @Override
    public MemoryContext loadForRun(IntentMemoryRequest request) {
        if (!properties.contextEnabled()) {
            return MemoryContext.empty();
        }
        List<IntentMessageSnapshot> recentMessages = properties.getShortTerm().isEnabled()
                ? messages.findRecentMessages(request.tenantId(), request.userId(), request.sessionId(),
                        properties.getShortTerm().recentMessageLimit())
                : List.of();
        List<IntentLongTermMemorySnapshot> longTermMemories = properties.getLongTerm().isEnabled()
                ? longTermMemory.searchRelevant(request.tenantId(), request.userId(), request.query(),
                        properties.getLongTerm().normalizedTopK()).stream()
                        .map(MemoryApplicationService::snapshot)
                        .toList()
                : List.of();
        return new MemoryContext(recentMessages, longTermMemories);
    }

    private static IntentLongTermMemorySnapshot snapshot(LongTermMemoryItem item) {
        return new IntentLongTermMemorySnapshot(
                item.id(), item.tenantId(), item.userId(), item.memoryType(), item.content(),
                item.confidence(), item.createdAt());
    }
}
