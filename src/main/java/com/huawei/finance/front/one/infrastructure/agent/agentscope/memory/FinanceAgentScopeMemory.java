package com.huawei.finance.front.one.infrastructure.agent.agentscope.memory;

import com.huawei.finance.front.one.application.gateway.AgentRunRequest;
import com.huawei.finance.front.one.application.gateway.ChatMessageRepository;
import com.huawei.finance.front.one.application.gateway.IdGenerateContext;
import com.huawei.finance.front.one.application.gateway.IdGenerator;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 项目存储驱动的 AgentScope 短期记忆。
 *
 * <p>构造时加载会话历史；AgentScope 运行过程中调用 addMessage 时，同步写回项目消息仓储。
 * 这样 AgentScope 负责记忆调用时机，项目负责真正的存储、隔离和后续替换实现。</p>
 */
public class FinanceAgentScopeMemory implements Memory {
    private static final String HISTORY_SOURCE = "project-short-memory";

    private final AgentRunRequest request;
    private final ChatMessageRepository chatMessages;
    private final IdGenerator idGenerator;
    private final List<Msg> historyMessages;
    private final List<Msg> runtimeMessages = new CopyOnWriteArrayList<>();
    private final Set<String> persistedRuntimeKeys = ConcurrentHashMap.newKeySet();

    public FinanceAgentScopeMemory(AgentRunRequest request, ChatMessageRepository chatMessages, IdGenerator idGenerator) {
        this.request = request;
        this.chatMessages = chatMessages;
        this.idGenerator = idGenerator;
        this.historyMessages = loadHistory(request);
    }

    @Override
    public void addMessage(Msg msg) {
        if (msg == null) {
            return;
        }
        runtimeMessages.add(msg);
        persistVisibleMessage(msg);
    }

    @Override
    public List<Msg> getMessages() {
        List<Msg> messages = new ArrayList<>(historyMessages.size() + runtimeMessages.size());
        messages.addAll(historyMessages);
        messages.addAll(runtimeMessages);
        return messages;
    }

    @Override
    public void deleteMessage(int index) {
        if (index < historyMessages.size()) {
            return;
        }
        int runtimeIndex = index - historyMessages.size();
        if (runtimeIndex >= 0 && runtimeIndex < runtimeMessages.size()) {
            runtimeMessages.remove(runtimeIndex);
        }
    }

    @Override
    public void clear() {
        // 只清理本次 Agent 运行内的临时消息，不在这里删除项目持久化的会话历史。
        runtimeMessages.clear();
    }

    private List<Msg> loadHistory(AgentRunRequest request) {
        List<Msg> messages = new ArrayList<>();
        request.memoryContext().conversationSummary().ifPresent(summary -> messages.add(Msg.builder()
                .id(summary.id())
                .name("conversation_summary")
                .role(MsgRole.SYSTEM)
                .textContent("历史摘要：" + summary.summaryText())
                .timestamp(summary.createdAt().toString())
                .metadata(metadata(HISTORY_SOURCE, request.tenantId(), request.userId(), request.sessionId()))
                .build()));

        request.memoryContext().recentMessages().stream()
                .sorted(Comparator.comparing(ChatMessage::createdAt))
                .map(this::toAgentScopeMessage)
                .forEach(messages::add);
        return List.copyOf(messages);
    }

    private Msg toAgentScopeMessage(ChatMessage message) {
        return Msg.builder()
                .id(message.id())
                .name(message.role())
                .role(toAgentScopeRole(message.role()))
                .textContent(message.content())
                .timestamp(message.createdAt().toString())
                .metadata(metadata(HISTORY_SOURCE, message.tenantId(), message.userId(), message.sessionId()))
                .build();
    }

    private Map<String, Object> metadata(String source, String tenantId, String userId, String sessionId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", source);
        if (tenantId != null) metadata.put("tenantId", tenantId);
        if (userId != null) metadata.put("userId", userId);
        if (sessionId != null) metadata.put("sessionId", sessionId);
        return metadata;
    }

    private MsgRole toAgentScopeRole(String role) {
        if ("assistant".equalsIgnoreCase(role)) {
            return MsgRole.ASSISTANT;
        }
        if ("system".equalsIgnoreCase(role)) {
            return MsgRole.SYSTEM;
        }
        if ("tool".equalsIgnoreCase(role)) {
            return MsgRole.TOOL;
        }
        return MsgRole.USER;
    }

    private void persistVisibleMessage(Msg msg) {
        MsgRole role = msg.getRole();
        if (role != MsgRole.USER && role != MsgRole.ASSISTANT) {
            return;
        }
        if (isToolOnlyAssistantMessage(msg)) {
            return;
        }
        String text = msg.getTextContent();
        if (text == null || text.isBlank()) {
            return;
        }
        String key = role.name() + "\n" + text;
        if (!persistedRuntimeKeys.add(key)) {
            return;
        }

        // 只持久化用户和助手可见文本；工具过程消息保留在 AgentScope 本次运行内，避免污染前端会话历史。
        String messageId = idGenerator.newId("msg", IdGenerateContext.of(request.tenantId(), request.userId(), request.sessionId()));
        chatMessages.save(new ChatMessage(
                messageId,
                request.tenantId(),
                request.userId(),
                request.sessionId(),
                role == MsgRole.ASSISTANT ? "assistant" : "user",
                text,
                null,
                Instant.now()
        ));
    }

    private boolean isToolOnlyAssistantMessage(Msg msg) {
        GenerateReason reason = msg.getGenerateReason();
        return msg.getRole() == MsgRole.ASSISTANT && (reason == GenerateReason.TOOL_CALLS || reason == GenerateReason.TOOL_SUSPENDED);
    }
}
