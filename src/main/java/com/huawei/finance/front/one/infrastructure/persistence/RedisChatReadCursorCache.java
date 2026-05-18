package com.huawei.finance.front.one.infrastructure.persistence;

import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorCache;
import com.huawei.finance.front.one.application.config.ChatReadCursorProperties;
import com.huawei.finance.front.one.domain.chat.ChatReadCursor;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 聊天事件消费游标 Redis 热缓存实现。
 *
 * <p>缓存值只保存 lastConsumedSeq，身份和会话维度体现在 key 中。写入时保持单调递增，
 * 避免旧设备的较小 ack 覆盖新设备已经确认的更大游标。</p>
 */
@Component
@EnableConfigurationProperties(ChatReadCursorProperties.class)
public class RedisChatReadCursorCache implements ChatReadCursorCache {
    private static final Logger log = LoggerFactory.getLogger(RedisChatReadCursorCache.class);
    private static final String UNKNOWN = "_";

    private final StringRedisTemplate redis;
    private final ChatReadCursorProperties properties;

    public RedisChatReadCursorCache(StringRedisTemplate redis, ChatReadCursorProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public Optional<ChatReadCursor> find(String tenantId, String userId, String sessionId) {
        try {
            String value = redis.opsForValue().get(key(tenantId, userId, sessionId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new ChatReadCursor(null, tenantId, userId, sessionId,
                    Long.parseLong(value), Instant.now()));
        } catch (RuntimeException ex) {
            log.warn("ChatReadCursor Redis 读取失败，将回源 openGauss。原因：{}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(ChatReadCursor cursor) {
        if (cursor == null || cursor.sessionId() == null || cursor.sessionId().isBlank()) {
            return;
        }
        try {
            String key = key(cursor.tenantId(), cursor.userId(), cursor.sessionId());
            String currentValue = redis.opsForValue().get(key);
            long currentSeq = currentValue == null || currentValue.isBlank() ? 0L : Long.parseLong(currentValue);
            if (cursor.lastConsumedSeq() >= currentSeq) {
                redis.opsForValue().set(key, String.valueOf(cursor.lastConsumedSeq()), properties.getRedisTtl());
            }
        } catch (RuntimeException ex) {
            log.warn("ChatReadCursor Redis 写入失败，openGauss 仍作为事实源。原因：{}", ex.getMessage());
        }
    }

    private String key(String tenantId, String userId, String sessionId) {
        return properties.getRedisKeyPrefix() + ":" + normalize(tenantId) + ":" + normalize(userId) + ":" + normalize(sessionId);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
