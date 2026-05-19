package com.huawei.finance.front.one.infrastructure.persistence;

import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.domain.chat.ChatReadCursor;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
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
        long normalizedSeq = Math.max(0L, lastConsumedSeq);
        Instant updatedAt = Instant.now();
        int updated = mapper.updateMax(tenantId, userId, sessionId, normalizedSeq, updatedAt);
        if (updated == 0) {
            try {
                mapper.insert(id, tenantId, userId, sessionId, normalizedSeq, updatedAt);
            } catch (DuplicateKeyException ex) {
                // 多设备首次 ack 同时到达时，唯一键可能已由另一事务插入；再次执行单调更新即可。
                mapper.updateMax(tenantId, userId, sessionId, normalizedSeq, updatedAt);
            }
        }
        return find(tenantId, userId, sessionId)
                .orElseThrow(() -> new IllegalStateException("聊天消费游标写入后回读失败: " + sessionId));
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
