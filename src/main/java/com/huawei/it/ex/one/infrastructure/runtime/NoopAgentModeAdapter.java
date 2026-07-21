package com.huawei.it.ex.one.infrastructure.runtime;

import com.huawei.it.ex.one.application.integration.agent.AgentModeAdapter;
import com.huawei.it.ex.one.application.integration.agent.AgentModeOutboundParameters;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 默认模式转换器：仅保留 binding 记录，不增加任何下游参数。 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class NoopAgentModeAdapter implements AgentModeAdapter {
    @Override
    public boolean supports(String provider) {
        return true;
    }

    @Override
    public AgentModeOutboundParameters adapt(AgentModeProfile profile, String provider, String targetId) {
        return AgentModeOutboundParameters.empty();
    }

    @Override
    public boolean fallback() {
        return true;
    }
}
