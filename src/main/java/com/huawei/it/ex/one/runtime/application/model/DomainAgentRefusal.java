package com.huawei.it.ex.one.runtime.application.model;

import com.huawei.it.ex.one.common.event.ChatEvent;

/** Stable refusal view derived from a normalized DomainAgent control event. */
public record DomainAgentRefusal(
        String code,
        String reasonCode,
        Boolean recoverable,
        String message,
        String agentId
) {
    public static DomainAgentRefusal from(ChatEvent event) {
        if (event == null || event.payload() == null) {
            return null;
        }
        return DomainAgentControlEvent.fromNormalizedPayload(event.payload())
                .filter(DomainAgentControlEvent::reroute)
                .map(control -> new DomainAgentRefusal(
                        control.code(), control.reasonCode(), control.recoverable(),
                        control.message(), control.agentId()))
                .orElse(null);
    }
}
