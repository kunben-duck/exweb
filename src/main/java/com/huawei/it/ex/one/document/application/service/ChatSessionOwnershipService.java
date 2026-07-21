package com.huawei.it.ex.one.document.application.service;

import com.huawei.it.ex.one.security.domain.UserContext;

/** Exposes session ownership validation without leaking the chat repository or domain model. */
public interface ChatSessionOwnershipService {
    void requireOwnedSession(UserContext user, String sessionId);
}
