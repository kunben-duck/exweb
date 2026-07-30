package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;

import java.util.Set;

/** Run-local state used while selecting a replacement after a DomainAgent refusal. */
record DomainAgentRerouteContext(
        DomainAgentRunContext context,
        DomainAgentRefusal refusal,
        DomainAgentRejectReason lastIntentRejectReason,
        String currentDomainAgentId,
        Set<String> rejectedDomainAgentIds,
        String intentQuery,
        AgentModeProfile agentMode
) {
}
