/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.routing.RouteTarget;

/** RouteMemory decision held until an attachment-rejection terminal commit succeeds. */
record PendingRouteMemoryDecision(
        UserContext user,
        String sessionId,
        String runId,
        String query,
        IntentDecision intent,
        RouteTarget route
) {
}
