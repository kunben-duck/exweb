package com.huawei.finance.front.one.infrastructure.memory;

import com.huawei.finance.front.one.application.gateway.ChatMessageRepository;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * 短期记忆组合仓储。
 *
 * <p>写入时先写 Redis，再写数据库；读取时优先查 Redis，Redis 为空或过期时回源数据库并预热缓存。</p>
 */
@Primary
@Repository
@EnableConfigurationProperties(ShortTermMemoryStorageProperties.class)
public class LayeredChatMessageRepository implements ChatMessageRepository {
    private static final Logger log = LoggerFactory.getLogger(LayeredChatMessageRepository.class);

    private final RedisShortTermMemoryCache redisCache;
    private final MyBatisChatMessageStore databaseStore;
    private final ShortTermMemoryStorageProperties properties;
    private volatile Instant databaseRetryAfter = Instant.MIN;

    public LayeredChatMessageRepository(RedisShortTermMemoryCache redisCache, MyBatisChatMessageStore databaseStore,
                                        ShortTermMemoryStorageProperties properties) {
        this.redisCache = redisCache;
        this.databaseStore = databaseStore;
        this.properties = properties;
    }

    @Override
    public ChatMessage save(ChatMessage message) {
        boolean cached = redisCache.append(message);
        if (!canUseDatabase()) {
            return message;
        }
        try {
            return databaseStore.save(message);
        } catch (RuntimeException ex) {
            // 生产可开启 databaseRequired 强制失败；本地 mock 环境允许降级，避免阻塞前端/架构联调。
            if (properties.isDatabaseRequired()) {
                if (cached) {
                    redisCache.remove(message);
                }
                throw ex;
            }
            markDatabaseFailure(ex);
            return message;
        }
    }

    @Override
    public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
        List<ChatMessage> cached = redisCache.findRecentMessages(tenantId, userId, sessionId, limit);
        if (!cached.isEmpty()) {
            return cached;
        }
        if (!canUseDatabase()) {
            return List.of();
        }
        List<ChatMessage> persisted;
        try {
            persisted = databaseStore.findRecentMessages(tenantId, userId, sessionId, limit);
        } catch (RuntimeException ex) {
            if (properties.isDatabaseRequired()) {
                throw ex;
            }
            markDatabaseFailure(ex);
            return List.of();
        }
        if (!persisted.isEmpty()) {
            redisCache.replaceSessionMessages(tenantId, userId, sessionId, persisted);
        }
        return persisted;
    }

    private boolean canUseDatabase() {
        return properties.isDatabaseRequired() || !Instant.now().isBefore(databaseRetryAfter);
    }

    private void markDatabaseFailure(RuntimeException ex) {
        databaseRetryAfter = Instant.now().plus(properties.getDatabaseFailureBackoff());
        log.warn("短期消息数据库暂不可用，{} 后重试；本次请求降级处理。原因：{}",
                properties.getDatabaseFailureBackoff(), ex.getMessage());
    }
}
