package com.huawei.finance.front.one.infrastructure.persistence;

import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.domain.chat.ChatReadCursor;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 聊天事件消费游标 openGauss 事实源实现。
 */
@Repository
public class OpenGaussChatReadCursorRepository implements ChatReadCursorRepository {
    private final ChatReadCursorMapper mapper;
    private final IdGenerator idGenerator;

    public OpenGaussChatReadCursorRepository(ChatReadCursorMapper mapper, IdGenerator idGenerator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public Optional<ChatReadCursor> find(String tenantId, String userId, String sessionId) {
        return Optional.ofNullable(mapper.find(tenantId, userId, sessionId)).map(this::toDomain);
    }

    @Override
    public ChatReadCursor upsert(String tenantId, String userId, String sessionId, long lastConsumedSeq) {
        String id = idGenerator.newId("cursor", IdGenerateContext.of(tenantId, userId, sessionId, null));
        ChatReadCursorRow row = mapper.upsert(id, tenantId, userId, sessionId, Math.max(0L, lastConsumedSeq), Instant.now());
        return toDomain(row);
    }

    private ChatReadCursor toDomain(ChatReadCursorRow row) {
        return new ChatReadCursor(
                row.getId(),
                row.getTenantId(),
                row.getUserId(),
                row.getSessionId(),
                row.getLastConsumedSeq() == null ? 0L : row.getLastConsumedSeq(),
                row.getUpdatedAt()
        );
    }
}
