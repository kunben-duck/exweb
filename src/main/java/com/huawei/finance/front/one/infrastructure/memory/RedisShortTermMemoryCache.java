package com.huawei.finance.front.one.infrastructure.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 短期记忆缓存。
 *
 * <p>Redis 只承担最近问答热缓存，不作为最终事实源。只有短期记忆和缓存同时开启时才访问 Redis；
 * 当 Redis 不可用或 key 过期时，上层组合仓储会回退到数据库并重新预热 Redis。</p>
 */
@Component
public class RedisShortTermMemoryCache {
    private static final Logger log = LoggerFactory.getLogger(RedisShortTermMemoryCache.class);
    private static final String UNKNOWN = "_";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ShortTermMemoryRedisProperties properties;
    private volatile Instant retryAfter = Instant.MIN;

    public RedisShortTermMemoryCache(StringRedisTemplate redis, ObjectMapper objectMapper, ShortTermMemoryRedisProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean append(ChatMessage message) {
        if (!canUseRedis() || message == null || message.sessionId() == null) {
            return false;
        }
        try {
            String key = key(message.tenantId(), message.userId(), message.sessionId());
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
            List<String> values = redis.opsForList().range(key(tenantId, userId, sessionId), -limit, -1);
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
            messages.sort(Comparator.comparing(ChatMessage::createdAt).reversed());
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
            String key = key(tenantId, userId, sessionId);
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
            redis.opsForList().remove(key(message.tenantId(), message.userId(), message.sessionId()), 1, serialize(message));
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
            log.warn("短期记忆 Redis 暂不可用，{} 后重试；本次请求将回退数据库。", properties.getFailureBackoff());
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
            log.warn("忽略无法反序列化的 Redis 短期记忆消息。");
            return null;
        }
    }

    private String key(String tenantId, String userId, String sessionId) {
        return properties.getRedisKeyPrefix()
                + ":messages:"
                + normalize(tenantId)
                + ":"
                + normalize(userId)
                + ":"
                + normalize(sessionId);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }

    private int maxCachedMessages() {
        return Math.max(1, properties.getMaxCachedMessages());
    }
}
