package com.huawei.it.ex.one.application.service.chat;

import java.util.Set;

/** Persisted refusal context restored after Intent clarification. */
record DomainAgentRerouteState(
        String currentTargetId,
        String currentBindingId,
        String currentRouteSource,
        DomainAgentRefusal refusal,
        DomainAgentRejectReason lastIntentRejectReason,
        Set<String> rejectedDomainAgentIds,
        int rerouteCount
) {
}
