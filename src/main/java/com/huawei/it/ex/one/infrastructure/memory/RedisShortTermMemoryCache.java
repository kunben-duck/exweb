package com.huawei.it.ex.one.infrastructure.memory;

import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Redis 短期记忆缓存。
 *
 * <p>Redis 只承担最近问答热缓存，不作为最终事实源。只有短期记忆和缓存同时开启时才访问 Redis；
 * 当 Redis 不可用或 key 过期时，上层组合仓储会回退到数据库并重新预热 Redis。</p>
 */
@Component
public class RedisShortTermMemoryCache {
    private static final AppLogger log = AppLoggerFactory.getLogger(RedisShortTermMemoryCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ShortTermMemoryRedisProperties properties;
    private final FinanceExRedisKeyBuilder redisKeys;
    private volatile Instant retryAfter = Instant.MIN;

    public RedisShortTermMemoryCache(StringRedisTemplate redis, ObjectMapper objectMapper,
                                     ShortTermMemoryRedisProperties properties,
                                     FinanceExRedisKeyBuilder redisKeys) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.redisKeys = redisKeys;
    }

    public boolean append(ChatMessage message) {
        if (!canUseRedis() || message == null || message.sessionId() == null) {
            return false;
        }
        try {
            String key = redisKeys.shortTermMemoryMessages(message.tenantId(), message.userId(), message.sessionId());
            redis.opsForList().rightPush(key, serialize(message));
            redis.opsForList().trim(key, -maxCachedMessages(), -1);
            redis.expire(key, properties.getTtl());
            return true;
        } catch (RuntimeException ex) {
            markRedisFailure(ex);
            return false;
        }
    }

    public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
        if (!canUseRedis() || sessionId == null || limit <= 0) {
            return List.of();
        }
        try {
            String key = redisKeys.shortTermMemoryMessages(tenantId, userId, sessionId);
            List<String> values = redis.opsForList().range(key, -limit, -1);
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            List<ChatMessage> messages = new ArrayList<>(values.size());
            for (String value : values) {
                ChatMessage message = deserialize(value);
                if (message != null) {
                    messages.add(message);
                }
            }
            // Redis 列表保存最近消息窗口；返回给 MemoryContext 时使用阅读顺序，避免下游上下文倒序。
            messages.sort(Comparator.comparing(ChatMessage::createdAt));
            return messages;
        } catch (RuntimeException ex) {
            markRedisFailure(ex);
            return List.of();
        }
    }

    public void replaceSessionMessages(String tenantId, String userId, String sessionId, List<ChatMessage> messages) {
        if (!canUseRedis() || sessionId == null || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            String key = redisKeys.shortTermMemoryMessages(tenantId, userId, sessionId);
            redis.delete(key);
            List<String> values = messages.stream()
                    .sorted(Comparator.comparing(ChatMessage::createdAt).reversed())
                    .limit(maxCachedMessages())
                    .sorted(Comparator.comparing(ChatMessage::createdAt))
                    .map(this::serialize)
                    .toList();
            if (!values.isEmpty()) {
                redis.opsForList().rightPushAll(key, values);
                redis.expire(key, properties.getTtl());
            }
        } catch (RuntimeException ex) {
            markRedisFailure(ex);
        }
    }

    public void remove(ChatMessage message) {
        if (!canUseRedis() || message == null || message.sessionId() == null) {
            return;
        }
        try {
            String key = redisKeys.shortTermMemoryMessages(message.tenantId(), message.userId(), message.sessionId());
            redis.opsForList().remove(key, 1, serialize(message));
        } catch (RuntimeException ex) {
            markRedisFailure(ex);
        }
    }

    private boolean canUseRedis() {
        return properties.isEnabled() && properties.isCacheEnabled() && !Instant.now().isBefore(retryAfter);
    }

    private void markRedisFailure(RuntimeException ex) {
        if (isRedisConnectionProblem(ex)) {
            retryAfter = Instant.now().plus(properties.getFailureBackoff());
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_UNAVAILABLE,
                            "Short-term memory Redis is unavailable; falling back to database")
                    .operation("short-term-memory.cache.access")
                    .attribute("failureBackoff", properties.getFailureBackoff())
                    .build(), ex);
            return;
        }
        throw ex;
    }

    private boolean isRedisConnectionProblem(RuntimeException ex) {
        return ex instanceof RedisConnectionFailureException
                || ex instanceof RedisSystemException
                || ex.getCause() instanceof RedisConnectionFailureException;
    }

    private String serialize(ChatMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("短期记忆消息序列化失败", ex);
        }
    }

    private ChatMessage deserialize(String value) {
        try {
            return objectMapper.readValue(value, ChatMessage.class);
        } catch (JsonProcessingException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_DESERIALIZATION_FAILED,
                            "Ignoring an invalid short-term memory Redis entry")
                    .operation("short-term-memory.cache.deserialize")
                    .build(), ex);
            return null;
        }
    }

    private int maxCachedMessages() {
        return Math.max(1, properties.getMaxCachedMessages());
    }
}
