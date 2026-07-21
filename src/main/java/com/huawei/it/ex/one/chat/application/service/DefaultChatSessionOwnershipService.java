package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.repository.SessionRepository;
import com.huawei.it.ex.one.document.application.service.ChatSessionOwnershipService;
import com.huawei.it.ex.one.security.domain.UserContext;
import org.springframework.stereotype.Service;

/** Repository-backed session ownership check used by neighboring application services. */
@Service
public class DefaultChatSessionOwnershipService implements ChatSessionOwnershipService {
    private final SessionRepository sessionRepository;

    public DefaultChatSessionOwnershipService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    public void requireOwnedSession(UserContext user, String sessionId) {
        if (sessionRepository.findByTenantIdAndUserIdAndId(
                user.tenantId(), user.ownerUserId(), sessionId).isEmpty()) {
            throw new SecurityException("文档不能绑定到不属于当前用户的会话");
        }
    }
}
