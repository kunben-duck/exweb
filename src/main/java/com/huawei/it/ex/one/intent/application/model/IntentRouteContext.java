package com.huawei.it.ex.one.intent.application.model;

import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import java.util.Map;

/** Internal snapshot used while one IntentAgent route decision is assembled. */
public record IntentRouteContext(
        UserContext user,
        IntentSessionSnapshot session,
        IntentCommandSnapshot command,
        MemoryContext memory,
        String routeTrigger,
        Map<String, Object> lastRejectReason,
        String runId
) {
}
