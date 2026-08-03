package com.huawei.it.ex.one.application.service.memory;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.integration.memory.LongTermMemoryStore;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.memory.LongTermMemoryItem;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

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
    private final AgentDataPersistenceProperties persistenceProperties;
    private final ShortTermMemoryContextAssembler shortTermAssembler;

    @Autowired
    public MemoryApplicationService(ChatMessageRepository messages, LongTermMemoryStore longTermMemory,
                                    MemoryProperties properties,
                                    AgentDataPersistenceProperties persistenceProperties,
                                    ShortTermMemoryContextAssembler shortTermAssembler) {
        this.messages = messages;
        this.longTermMemory = longTermMemory;
        this.properties = properties;
        this.persistenceProperties = persistenceProperties;
        this.shortTermAssembler = shortTermAssembler;
    }

    public MemoryApplicationService(ChatMessageRepository messages, LongTermMemoryStore longTermMemory,
                                    MemoryProperties properties,
                                    AgentDataPersistenceProperties persistenceProperties) {
        this(messages, longTermMemory, properties, persistenceProperties, null);
    }

    public MemoryApplicationService(ChatMessageRepository messages, LongTermMemoryStore longTermMemory,
                                    MemoryProperties properties) {
        this(messages, longTermMemory, properties, new AgentDataPersistenceProperties());
    }

    /**
     * 根据配置为本轮 run 装配可选记忆上下文。
     *
     * @param command 已由应用层回填身份和会话的聊天命令。
     * @return 记忆上下文；全部记忆关闭时返回空上下文。
     */
    public MemoryContext loadForRun(ChatCommand command) {
        return loadForRun(command, null);
    }

    /**
     * 根据当前 active path 叶子装配本轮不可变记忆快照。
     *
     * @param command 当前聊天命令。
     * @param currentLeafMessageId 读取记忆时会话的当前叶子；本轮用户消息尚未落库。
     * @return 本轮记忆上下文。
     */
    public MemoryContext loadForRun(ChatCommand command, String currentLeafMessageId) {
        return loadForRun(command, currentLeafMessageId, null);
    }

    /**
     * 装配 Interaction continuation 的记忆，并排除已经存在于历史路径中的本轮 query 消息。
     */
    public MemoryContext loadForRun(
            ChatCommand command, String currentLeafMessageId, String excludedMessageId) {
        return loadForRun(command, currentLeafMessageId, excludedMessageId, false);
    }

    /**
     * 装配指定消息路径的记忆；新分支位于根节点前时可显式跳过短期消息读取。
     */
    public MemoryContext loadForRun(
            ChatCommand command,
            String currentLeafMessageId,
            String excludedMessageId,
            boolean emptyShortTermPath) {
        if (!properties.contextEnabled()) {
            return MemoryContext.empty();
        }
        int sourceLimit = shortTermAssembler == null
                ? properties.getShortTerm().sourceMessageLimit()
                : shortTermAssembler.sourceMessageLimit();
        List<ChatMessage> recentMessages = properties.getShortTerm().isEnabled() && !emptyShortTermPath
                ? messages.findRecentMessages(command.tenantId(), command.userId(), command.sessionId(),
                        currentLeafMessageId, sourceLimit).stream()
                        .filter(message -> message != null
                                && !Objects.equals(excludedMessageId, message.id()))
                        .filter(this::memoryEligibleMessage)
                        .toList()
                : List.of();
        List<LongTermMemoryItem> longTermMemories = properties.getLongTerm().isEnabled()
                ? longTermMemory.searchRelevant(command.tenantId(), command.userId(), command.message(),
                        properties.getLongTerm().normalizedTopK()).stream()
                        .filter(memory -> !placeholderMemory(memory))
                        .toList()
                : List.of();
        return new MemoryContext(
                recentMessages,
                longTermMemories,
                null,
                properties.getShortTerm().isEnabled(),
                shortTermAssembler == null ? List.of() : shortTermAssembler.agentRuntimeMessages(recentMessages));
    }

    private boolean placeholderAssistant(ChatMessage message) {
        return message != null
                && "assistant".equalsIgnoreCase(message.role())
                && AgentDataPersistenceMetadata.placeholderAssistant(message.metadataJson());
    }

    private boolean memoryEligibleMessage(ChatMessage message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            return false;
        }
        boolean supportedRole = "user".equalsIgnoreCase(message.role())
                || "assistant".equalsIgnoreCase(message.role());
        return supportedRole && !placeholderAssistant(message);
    }

    private boolean placeholderMemory(LongTermMemoryItem memory) {
        return memory != null
                && persistenceProperties.normalizedPlaceholderContent().equals(memory.content());
    }
}
