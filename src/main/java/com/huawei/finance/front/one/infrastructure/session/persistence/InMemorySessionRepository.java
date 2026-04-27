package com.huawei.finance.front.one.infrastructure.session.persistence;

import com.huawei.finance.front.one.application.gateway.SessionRepository;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 第一版会话仓储实现。
 *
 * <p>当前使用内存存储保证本地启动和联调简单；生产实现应在同包下替换为 PostgreSQL / MyBatis。</p>
 */
@Repository
public class InMemorySessionRepository implements SessionRepository {
    private final Map<String, ChatSession> store = new ConcurrentHashMap<>();

    @Override
    public Optional<ChatSession> findById(String sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }

    @Override
    public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
        return findById(sessionId).filter(session -> session.tenantId().equals(tenantId) && session.userId().equals(userId));
    }

    @Override
    public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) {
        return store.values().stream()
                .filter(session -> session.tenantId().equals(tenantId) && session.userId().equals(userId))
                .sorted(Comparator.comparing(ChatSession::updatedAt).reversed())
                .toList();
    }

    @Override
    public ChatSession save(ChatSession session) {
        store.put(session.id(), session);
        return session;
    }
}
