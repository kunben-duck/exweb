package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;

import org.springframework.stereotype.Component;

/** 为生产run创建共享实例预算约束下的AssistantAssembly。 */
@Component
final class AssistantAssemblyFactory {
    private final AssistantAssemblyBudgetRegistry budgetRegistry;

    AssistantAssemblyFactory(AssistantAssemblyBudgetRegistry budgetRegistry) {
        this.budgetRegistry = budgetRegistry;
    }

    AssistantAssembly create(String runId) {
        return create(runId, AgentDataPersistenceState.full());
    }

    AssistantAssembly create(String runId, AgentDataPersistenceState persistenceState) {
        return new AssistantAssembly(
                persistenceState,
                budgetRegistry,
                budgetRegistry.open(runId));
    }
}
