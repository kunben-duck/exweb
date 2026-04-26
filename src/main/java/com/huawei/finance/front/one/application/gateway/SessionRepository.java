package com.huawei.finance.front.one.application.gateway;

import com.huawei.finance.front.one.domain.chat.ChatSession;
import java.util.List;
import java.util.Optional;

public interface SessionRepository {
    Optional<ChatSession> findById(String sessionId);
    Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId);
    List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId);
    ChatSession save(ChatSession session);
}
