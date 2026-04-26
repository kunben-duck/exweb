package com.huawei.finance.front.one.infrastructure.persistence;

import com.huawei.finance.front.one.application.gateway.SessionRepository;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemorySessionRepository implements SessionRepository {
    private final Map<String, ChatSession> store = new ConcurrentHashMap<>();
    @Override public Optional<ChatSession> findById(String sessionId) { return Optional.ofNullable(store.get(sessionId)); }
    @Override public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
        return findById(sessionId).filter(session -> session.tenantId().equals(tenantId) && session.userId().equals(userId));
    }
    @Override public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) {
        return store.values().stream()
                .filter(session -> session.tenantId().equals(tenantId) && session.userId().equals(userId))
                .sorted(Comparator.comparing(ChatSession::updatedAt).reversed())
                .toList();
    }
    @Override public ChatSession save(ChatSession session) { store.put(session.id(), session); return session; }
}
