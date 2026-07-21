package com.huawei.it.ex.one.intent.application.model;

import com.huawei.it.ex.one.security.domain.UserContext;

/** Immutable command for recording an applied route decision. */
public record RouteMemoryRouteCommand(
        UserContext user,
        String sessionId,
        String sourceRunId,
        String query,
        IntentDecision intent,
        RouteTarget route
) {
}
