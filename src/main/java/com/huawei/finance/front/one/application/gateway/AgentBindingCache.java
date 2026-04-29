package com.huawei.finance.front.one.application.gateway;

import com.huawei.finance.front.one.domain.agent.AgentBinding;
import java.util.Optional;

public interface AgentBindingCache {
    Optional<AgentBinding> get(String tenantId, String userId, String sessionId);
    void put(AgentBinding binding);
    void evict(String tenantId, String userId, String sessionId);
}
