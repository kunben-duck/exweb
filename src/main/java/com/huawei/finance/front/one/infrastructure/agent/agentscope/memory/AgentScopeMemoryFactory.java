package com.huawei.finance.front.one.infrastructure.agent.agentscope.memory;

import com.huawei.finance.front.one.application.gateway.AgentRunRequest;
import com.huawei.finance.front.one.application.gateway.ChatMessageRepository;
import com.huawei.finance.front.one.application.gateway.IdGenerator;
import com.huawei.finance.front.one.application.gateway.LongTermMemoryStore;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.Memory;
import org.springframework.stereotype.Component;

/**
 * AgentScope 记忆工厂。
 *
 * <p>这里是项目记忆体系和 AgentScope 原生记忆接口的适配边界；
 * application 层仍然只认识项目自己的 MemoryContext 和 Repository。</p>
 */
@Component
public class AgentScopeMemoryFactory {
    private final ChatMessageRepository chatMessages;
    private final LongTermMemoryStore longTermMemoryStore;
    private final IdGenerator idGenerator;

    public AgentScopeMemoryFactory(ChatMessageRepository chatMessages, LongTermMemoryStore longTermMemoryStore, IdGenerator idGenerator) {
        this.chatMessages = chatMessages;
        this.longTermMemoryStore = longTermMemoryStore;
        this.idGenerator = idGenerator;
    }

    public Memory shortTermMemory(AgentRunRequest request) {
        return new FinanceAgentScopeMemory(request, chatMessages, idGenerator);
    }

    public LongTermMemory longTermMemory(AgentRunRequest request) {
        return new FinanceAgentScopeLongTermMemory(request, longTermMemoryStore, idGenerator);
    }
}
