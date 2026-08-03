package com.huawei.it.ex.one.infrastructure.memory;

import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
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
import java.util.List;
import java.util.Objects;

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
    private final MemoryProperties memoryProperties;
    private final FinanceExRedisKeyBuilder redisKeys;
    private volatile Instant retryAfter = Instant.MIN;

    public RedisShortTermMemoryCache(StringRedisTemplate redis, ObjectMapper objectMapper,
                                     ShortTermMemoryRedisProperties properties,
                                     MemoryProperties memoryProperties,
                                     FinanceExRedisKeyBuilder redisKeys) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.memoryProperties = memoryProperties;
        this.redisKeys = redisKeys;
    }

    public boolean append(ChatMessage message) {
        if (!canUseRedis() || message == null || message.sessionId() == null) {
            return false;
        }
        try {
            String key = redisKeys.shortTermMemoryMessages(message.tenantId(), message.userId(), message.sessionId());
            redis.opsForList().rightPush(key, serialize(toEntry(message)));
            redis.opsForList().trim(key, -cacheMessageLimit(), -1);
            redis.expire(key, properties.getTtl());
            return true;
        } catch (RuntimeException ex) {
            markRedisFailure(ex);
            return false;
        }
    }

    public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
        return findRecentMessages(tenantId, userId, sessionId, null, limit);
    }

    public List<ChatMessage> findRecentMessages(
            String tenantId, String userId, String sessionId, String expectedLeafMessageId, int limit) {
        if (!canUseRedis() || sessionId == null || limit <= 0 || limit > cacheMessageLimit()) {
            return List.of();
        }
        try {
            String key = redisKeys.shortTermMemoryMessages(tenantId, userId, sessionId);
            List<String> values = redis.opsForList().range(key, -limit, -1);
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            List<MemoryCacheEntry> entries = new ArrayList<>(values.size());
            for (String value : values) {
                MemoryCacheEntry entry = deserialize(value);
                if (entry == null) {
                    return List.of();
                }
                entries.add(entry);
            }
            if (!validPath(entries, expectedLeafMessageId, limit)) {
                return List.of();
            }
            return entries.stream()
                    .map(entry -> toMessage(tenantId, userId, sessionId, entry))
                    .toList();
        } catch (RuntimeException ex) {
            markRedisFailure(ex);
            return List.of();
        }
    }

    public void replaceSessionMessages(String tenantId, String userId, String sessionId, List<ChatMessage> messages) {
        if (!canUseRedis() || sessionId == null || messages == null) {
            return;
        }
        try {
            String key = redisKeys.shortTermMemoryMessages(tenantId, userId, sessionId);
            redis.delete(key);
            int fromIndex = Math.max(0, messages.size() - cacheMessageLimit());
            List<String> values = messages.subList(fromIndex, messages.size()).stream()
                    .map(this::toEntry)
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
            redis.opsForList().remove(key, 1, serialize(toEntry(message)));
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
        SystemErrorCode code = ex instanceof IllegalStateException
                ? SystemErrorCode.REDIS_SERIALIZATION_FAILED
                : SystemErrorCode.REDIS_READ_FAILED;
        log.warn(SystemErrorLogEntry.builder(code,
                        "Short-term memory Redis operation failed; falling back to database")
                .operation("short-term-memory.cache.access")
                .build(), ex);
    }

    private boolean isRedisConnectionProblem(RuntimeException ex) {
        return ex instanceof RedisConnectionFailureException
                || ex instanceof RedisSystemException
                || ex.getCause() instanceof RedisConnectionFailureException;
    }

    private String serialize(MemoryCacheEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Short-term memory entry serialization failed", ex);
        }
    }

    private MemoryCacheEntry deserialize(String value) {
        try {
            MemoryCacheEntry entry = objectMapper.readValue(value, MemoryCacheEntry.class);
            return validEntry(entry) ? entry : null;
        } catch (JsonProcessingException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_DESERIALIZATION_FAILED,
                            "Ignoring an invalid short-term memory Redis entry")
                    .operation("short-term-memory.cache.deserialize")
                    .build(), ex);
            return null;
        }
    }

    private boolean validPath(List<MemoryCacheEntry> entries, String expectedLeafMessageId, int requestedLimit) {
        if (entries.isEmpty()) {
            return false;
        }
        MemoryCacheEntry last = entries.getLast();
        if (expectedLeafMessageId != null && !expectedLeafMessageId.isBlank()
                && !expectedLeafMessageId.equals(last.messageId())) {
            return false;
        }
        for (int index = 1; index < entries.size(); index++) {
            if (!Objects.equals(entries.get(index).parentMessageId(), entries.get(index - 1).messageId())) {
                return false;
            }
        }
        return entries.size() >= requestedLimit
                || entries.getFirst().parentMessageId() == null
                || entries.getFirst().parentMessageId().isBlank();
    }

    private boolean validEntry(MemoryCacheEntry entry) {
        return entry != null
                && entry.messageId() != null
                && !entry.messageId().isBlank()
                && entry.role() != null
                && !entry.role().isBlank()
                && entry.createdAt() != null;
    }

    private MemoryCacheEntry toEntry(ChatMessage message) {
        boolean eligible = message != null
                && ("user".equalsIgnoreCase(message.role()) || "assistant".equalsIgnoreCase(message.role()))
                && message.content() != null
                && !message.content().isBlank()
                && !("assistant".equalsIgnoreCase(message.role())
                && AgentDataPersistenceMetadata.placeholderAssistant(message.metadataJson()));
        return new MemoryCacheEntry(
                message.id(),
                message.parentMessageId(),
                message.nodeOrder(),
                message.runId(),
                message.role(),
                eligible ? message.content() : null,
                eligible,
                message.createdAt());
    }

    private ChatMessage toMessage(
            String tenantId, String userId, String sessionId, MemoryCacheEntry entry) {
        return new ChatMessage(
                entry.messageId(),
                tenantId,
                userId,
                sessionId,
                entry.parentMessageId(),
                entry.nodeOrder(),
                0,
                0,
                entry.role(),
                entry.memoryEligible() ? entry.content() : null,
                null,
                entry.runId(),
                "NORMAL",
                false,
                null,
                null,
                null,
                null,
                null,
                entry.createdAt());
    }

    private int cacheMessageLimit() {
        return memoryProperties.getShortTerm().cacheMessageLimit();
    }

    /** Redis 只保存上下文组装及 active path 校验需要的紧凑字段。 */
    private record MemoryCacheEntry(
            String messageId,
            String parentMessageId,
            Long nodeOrder,
            String runId,
            String role,
            String content,
            boolean memoryEligible,
            Instant createdAt
    ) {
    }
}
