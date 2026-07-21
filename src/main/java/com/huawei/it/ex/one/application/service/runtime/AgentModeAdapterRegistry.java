package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.application.integration.agent.AgentModeAdapter;
import com.huawei.it.ex.one.application.integration.agent.AgentModeOutboundParameters;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import java.util.List;
import org.springframework.stereotype.Service;

/** 按 provider 选择 Agent 模式协议转换器。 */
@Service
public class AgentModeAdapterRegistry {
    private final List<AgentModeAdapter> adapters;

    public AgentModeAdapterRegistry(List<AgentModeAdapter> adapters) {
        this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
    }

    public AgentModeOutboundParameters adapt(AgentModeProfile profile, String provider, String targetId) {
        if (profile == null) {
            return AgentModeOutboundParameters.empty();
        }
        return adapters.stream()
                .filter(adapter -> !adapter.fallback() && adapter.supports(provider))
                .findFirst()
                .map(adapter -> adapter.adapt(profile, provider, targetId))
                .orElseGet(() -> adapters.stream()
                        .filter(AgentModeAdapter::fallback)
                        .filter(adapter -> adapter.supports(provider))
                        .findFirst()
                        .map(adapter -> adapter.adapt(profile, provider, targetId))
                        .orElseGet(AgentModeOutboundParameters::empty));
    }
}
