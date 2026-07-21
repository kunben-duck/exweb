package com.huawei.it.ex.one.chat.application.service;

import java.time.Instant;

/** Extension point invoked inside the existing session deletion transaction. */
public interface ChatSessionLifecycleService {
    void revokeActiveBySession(String tenantId, String userId, String sessionId, Instant revokedAt);
}
