package com.huawei.it.ex.one.chat.application.model;

import com.huawei.it.ex.one.runtime.application.model.DomainAgentRefusal;
import java.util.Set;

/** Persisted reroute facts restored after intent clarification. */
public record DomainAgentRerouteState(
        String currentTargetId,
        String currentBindingId,
        String currentRouteSource,
        DomainAgentRefusal refusal,
        Set<String> rejectedDomainAgentIds,
        int rerouteCount) {
}
