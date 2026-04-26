package com.huawei.finance.front.one.infrastructure.memory;

import com.huawei.finance.front.one.application.gateway.ChatMessageRepository;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * 短期记忆组合仓储。
 *
 * <p>写入时先写 Redis，再写数据库；读取时优先查 Redis，Redis 为空或过期时回源数据库并预热缓存。</p>
 */
@Primary
@Repository
public class LayeredChatMessageRepository implements ChatMessageRepository {
    private final RedisShortTermMemoryCache redisCache;
    private final MyBatisChatMessageStore databaseStore;

    public LayeredChatMessageRepository(RedisShortTermMemoryCache redisCache, MyBatisChatMessageStore databaseStore) {
        this.redisCache = redisCache;
        this.databaseStore = databaseStore;
    }

    @Override
    public ChatMessage save(ChatMessage message) {
        boolean cached = redisCache.append(message);
        try {
            return databaseStore.save(message);
        } catch (RuntimeException ex) {
            // 数据库仍是事实源；如果持久化失败，尽量撤销本次缓存写入，避免 Redis 暂存脏消息。
            if (cached) {
                redisCache.remove(message);
            }
            throw ex;
        }
    }

    @Override
    public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
        List<ChatMessage> cached = redisCache.findRecentMessages(tenantId, userId, sessionId, limit);
        if (!cached.isEmpty()) {
            return cached;
        }
        List<ChatMessage> persisted = databaseStore.findRecentMessages(tenantId, userId, sessionId, limit);
        if (!persisted.isEmpty()) {
            redisCache.replaceSessionMessages(tenantId, userId, sessionId, persisted);
        }
        return persisted;
    }
}
