package com.huawei.finance.front.one.infrastructure.agent.agentscope.memory;

import com.huawei.finance.front.one.application.gateway.AgentRunRequest;
import com.huawei.finance.front.one.application.gateway.IdGenerateContext;
import com.huawei.finance.front.one.application.gateway.IdGenerator;
import com.huawei.finance.front.one.application.gateway.LongTermMemoryStore;
import com.huawei.finance.front.one.domain.memory.LongTermMemoryItem;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * 项目长期记忆存储的 AgentScope 适配实现。
 *
 * <p>AgentScope 决定何时 retrieve/record；真正的召回、持久化、租户隔离和审计由项目 LongTermMemoryStore 负责。</p>
 */
public class FinanceAgentScopeLongTermMemory implements LongTermMemory {
    private static final int TOP_K = 5;

    private final AgentRunRequest request;
    private final LongTermMemoryStore store;
    private final IdGenerator idGenerator;

    public FinanceAgentScopeLongTermMemory(AgentRunRequest request, LongTermMemoryStore store, IdGenerator idGenerator) {
        this.request = request;
        this.store = store;
        this.idGenerator = idGenerator;
    }

    @Override
    public Mono<Void> record(List<Msg> messages) {
        return Mono.fromRunnable(() -> {
            String content = latestVisibleTurn(messages);
            if (content.isBlank()) {
                return;
            }
            String memoryId = idGenerator.newId("ltm", IdGenerateContext.of(request.tenantId(), request.userId(), request.sessionId()));
            store.save(new LongTermMemoryItem(memoryId, request.tenantId(), request.userId(), "conversation_turn", content, 1.0, Instant.now()));
        });
    }

    @Override
    public Mono<String> retrieve(Msg msg) {
        return Mono.fromSupplier(() -> {
            String query = msg == null ? "" : msg.getTextContent();
            if (query == null || query.isBlank()) {
                return "";
            }
            List<LongTermMemoryItem> memories = store.searchRelevant(request.tenantId(), request.userId(), query, TOP_K);
            if (memories == null || memories.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (LongTermMemoryItem item : memories) {
                if (item.content() == null || item.content().isBlank()) {
                    continue;
                }
                sb.append("- [")
                        .append(item.memoryType())
                        .append(", confidence=")
                        .append(item.confidence())
                        .append("] ")
                        .append(item.content())
                        .append("\n");
            }
            return sb.toString();
        });
    }

    private String latestVisibleTurn(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Msg message : messages) {
            if (message == null || isHistoryMessage(message)) {
                continue;
            }
            MsgRole role = message.getRole();
            if (role != MsgRole.USER && role != MsgRole.ASSISTANT) {
                continue;
            }
            if (isToolOnlyAssistantMessage(message)) {
                continue;
            }
            String text = message.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            sb.append(role == MsgRole.USER ? "user: " : "assistant: ")
                    .append(text)
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private boolean isHistoryMessage(Msg message) {
        Map<String, Object> metadata = message.getMetadata();
        return metadata != null && "project-short-memory".equals(metadata.get("source"));
    }

    private boolean isToolOnlyAssistantMessage(Msg message) {
        GenerateReason reason = message.getGenerateReason();
        return message.getRole() == MsgRole.ASSISTANT && (reason == GenerateReason.TOOL_CALLS || reason == GenerateReason.TOOL_SUSPENDED);
    }
}
