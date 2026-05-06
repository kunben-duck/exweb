package com.huawei.finance.front.one.infrastructure.session.persistence;

import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.infrastructure.session.persistence.mybatis.ChatSessionMapper;
import com.huawei.finance.front.one.infrastructure.session.persistence.mybatis.ChatSessionRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class OpenGaussSessionRepository implements SessionRepository {
    private final ChatSessionMapper mapper;

    public OpenGaussSessionRepository(ChatSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ChatSession> findById(String sessionId) {
        return Optional.ofNullable(mapper.findById(sessionId)).map(this::toDomain);
    }

    @Override
    public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
        return Optional.ofNullable(mapper.findByOwnerAndId(tenantId, userId, sessionId)).map(this::toDomain);
    }

    @Override
    public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) {
        return mapper.findByOwner(tenantId, userId).stream().map(this::toDomain).toList();
    }

    @Override
    public ChatSession save(ChatSession session) {
        mapper.upsert(session.id(), session.tenantId(), session.userId(), session.title(), session.status(),
                session.channel(), session.createdAt(), session.updatedAt());
        return session;
    }

    private ChatSession toDomain(ChatSessionRow row) {
        return new ChatSession(row.getId(), row.getTenantId(), row.getUserId(), row.getTitle(), row.getStatus(),
                row.getChannel(), row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt(),
                row.getUpdatedAt() == null ? Instant.EPOCH : row.getUpdatedAt());
    }
}
