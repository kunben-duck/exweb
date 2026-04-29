package com.huawei.finance.front.one.infrastructure.agent.runtime.agentscope.memory;

import com.huawei.finance.front.one.application.gateway.AgentRuntimeRequest;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 项目存储驱动的 AgentScope 短期记忆。
 *
 * <p>构造时加载会话历史；本次运行产生的临时消息只保留在 AgentScope 运行内。
 * 前端可见的 user/assistant 消息由 FinanceEXChatService 统一保存，避免重复写入。</p>
 */
public class FinanceAgentScopeMemory implements Memory {
    private static final String HISTORY_SOURCE = "project-short-memory";

    private final List<Msg> historyMessages;
    private final List<Msg> runtimeMessages = new CopyOnWriteArrayList<>();

    public FinanceAgentScopeMemory(AgentRuntimeRequest request) {
        this.historyMessages = loadHistory(request);
    }

    @Override
    public void addMessage(Msg msg) {
        if (msg == null) {
            return;
        }
        runtimeMessages.add(msg);
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

    private List<Msg> loadHistory(AgentRuntimeRequest request) {
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
        return MsgRole.USER;
    }

}
