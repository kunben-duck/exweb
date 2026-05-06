package com.huawei.finance.front.one.application.integration.agent.binding;

import com.huawei.finance.front.one.domain.agent.AgentBinding;
import java.util.Optional;

public interface AgentBindingRepository {
    Optional<AgentBinding> findActive(String tenantId, String userId, String sessionId);
    AgentBinding save(AgentBinding binding);
}
