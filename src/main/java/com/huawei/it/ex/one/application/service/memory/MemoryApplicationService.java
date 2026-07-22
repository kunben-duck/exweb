package com.huawei.it.ex.one.application.service.memory;

import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.integration.memory.LongTermMemoryStore;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.memory.LongTermMemoryItem;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 可选 SuperAgent 记忆上下文装配服务。
 *
 * <p>短期记忆和长期记忆均由配置独立控制。全部关闭时，该服务只返回空上下文，不访问 Redis、
 * 数据库历史消息或长期记忆服务。会话压缩、长上下文窗口和 Runtime 内部记忆仍属于
 * AgentRuntime 自治能力。</p>
 */
@Service
public class MemoryApplicationService {
    private final ChatMessageRepository messages;
    private final LongTermMemoryStore longTermMemory;
    private final MemoryProperties properties;

    public MemoryApplicationService(ChatMessageRepository messages, LongTermMemoryStore longTermMemory,
                                    MemoryProperties properties) {
        this.messages = messages;
        this.longTermMemory = longTermMemory;
        this.properties = properties;
    }

    /**
     * 根据配置为本轮 run 装配可选记忆上下文。
     *
     * @param command 已由应用层回填身份和会话的聊天命令。
     * @return 记忆上下文；全部记忆关闭时返回空上下文。
     */
    public MemoryContext loadForRun(ChatCommand command) {
        if (!properties.contextEnabled()) {
            return MemoryContext.empty();
        }
        List<ChatMessage> recentMessages = properties.getShortTerm().isEnabled()
                ? messages.findRecentMessages(command.tenantId(), command.userId(), command.sessionId(),
                        properties.getShortTerm().recentMessageLimit())
                : List.of();
        List<LongTermMemoryItem> longTermMemories = properties.getLongTerm().isEnabled()
                ? longTermMemory.searchRelevant(command.tenantId(), command.userId(), command.message(),
                        properties.getLongTerm().normalizedTopK())
                : List.of();
        return new MemoryContext(recentMessages, longTermMemories);
    }
}
