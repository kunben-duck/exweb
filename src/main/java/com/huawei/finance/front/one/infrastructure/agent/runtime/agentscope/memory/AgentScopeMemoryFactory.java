package com.huawei.finance.front.one.infrastructure.agent.runtime.agentscope.memory;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.memory.LongTermMemoryStore;
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
    private final LongTermMemoryStore longTermMemoryStore;
    private final IdGenerator idGenerator;

    public AgentScopeMemoryFactory(LongTermMemoryStore longTermMemoryStore, IdGenerator idGenerator) {
        this.longTermMemoryStore = longTermMemoryStore;
        this.idGenerator = idGenerator;
    }

    public Memory shortTermMemory(AgentRuntimeRequest request) {
        return new FinanceAgentScopeMemory(request);
    }

    public LongTermMemory longTermMemory(AgentRuntimeRequest request) {
        return new FinanceAgentScopeLongTermMemory(request, longTermMemoryStore, idGenerator);
    }
}
