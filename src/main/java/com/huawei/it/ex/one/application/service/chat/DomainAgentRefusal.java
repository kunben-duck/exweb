package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentControlEventMapper;

/** Stable application view of a DomainAgent refusal control event. */
record DomainAgentRefusal(
        String code,
        String reasonCode,
        Boolean recoverable,
        String message,
        String agentId
) {
    static DomainAgentRefusal from(ChatEvent event) {
        if (event == null || event.payload() == null) {
            return null;
        }
        return DomainAgentControlEventMapper.fromNormalizedPayload(event.payload())
                .filter(DomainAgentControlEventMapper.ControlEvent::reroute)
                .map(control -> new DomainAgentRefusal(
                        control.code(),
                        control.reasonCode(),
                        control.recoverable(),
                        control.message(),
                        control.agentId()))
                .orElse(null);
    }
}
