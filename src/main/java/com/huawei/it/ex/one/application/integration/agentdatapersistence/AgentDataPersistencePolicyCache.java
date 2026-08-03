package com.huawei.it.ex.one.application.integration.agentdatapersistence;

import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;

import java.time.Duration;
import java.util.Optional;

/** 跨实例共享的 Agent 数据留存策略缓存。 */
public interface AgentDataPersistencePolicyCache {
    Optional<AgentDataPersistencePolicy> get(String tenantId, String runtimeProvider, String skillId);

    void put(String tenantId, String runtimeProvider, String skillId,
             AgentDataPersistencePolicy policy, Duration ttl);
}
