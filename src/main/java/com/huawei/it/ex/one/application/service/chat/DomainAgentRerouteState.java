/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

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
        int rerouteCount,
        String confirmationInteractionId,
        boolean attemptCounted
) {
    DomainAgentRerouteState(String currentTargetId, String currentBindingId, String currentRouteSource,
                            DomainAgentRefusal refusal, DomainAgentRejectReason lastIntentRejectReason,
                            Set<String> rejectedDomainAgentIds, int rerouteCount, String confirmationInteractionId) {
        this(currentTargetId, currentBindingId, currentRouteSource, refusal, lastIntentRejectReason,
                rejectedDomainAgentIds, rerouteCount, confirmationInteractionId, false);
    }
}
