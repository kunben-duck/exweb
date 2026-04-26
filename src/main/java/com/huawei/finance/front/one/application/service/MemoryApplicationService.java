package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.gateway.ChatMessageRepository;
import com.huawei.finance.front.one.application.gateway.LongTermMemoryStore;
import com.huawei.finance.front.one.application.gateway.SummaryRepository;
import com.huawei.finance.front.one.application.gateway.WorkingMemoryStore;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 记忆上下文装配服务。
 *
 * <p>集中读取最近消息、会话摘要、工作记忆和长期记忆，避免 Agent/Runtime 各自拼上下文。</p>
 */
@Service
public class MemoryApplicationService {
    private final ChatMessageRepository messages;
    private final SummaryRepository summaries;
    private final WorkingMemoryStore workingMemory;
    private final LongTermMemoryStore longTermMemory;
    public MemoryApplicationService(ChatMessageRepository messages, SummaryRepository summaries, WorkingMemoryStore workingMemory, LongTermMemoryStore longTermMemory) {
        this.messages = messages; this.summaries = summaries; this.workingMemory = workingMemory; this.longTermMemory = longTermMemory;
    }
    public MemoryContext loadForRun(ChatCommand command) {
        // 当前策略保留最近 20 条消息，并检索 5 条相关长期记忆。
        return new MemoryContext(
                messages.findRecentMessages(command.tenantId(), command.userId(), command.sessionId(), 20),
                summaries.findLatestBySessionId(command.sessionId()),
                workingMemory.load(command.sessionId()),
                longTermMemory.searchRelevant(command.tenantId(), command.userId(), command.message(), 5)
        );
    }
    public void updateAfterRun(ChatCommand command, Map<String, Object> variables) {
        // 工作记忆用于保存轻量运行变量，例如最近一次 runId。
        workingMemory.update(command.sessionId(), variables == null ? Map.of() : variables);
    }
}
