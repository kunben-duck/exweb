package com.huawei.finance.front.one.infrastructure.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import com.huawei.finance.front.one.infrastructure.redis.FinanceExRedisKeyNamespace;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * RuntimeBinding Redis 热缓存实现。
 *
 * <p>Redis Cluster 下禁止使用 {@code KEYS} 做会话级模糊删除，因此每个会话维护一个同 slot
 * 的索引集合：binding key 与 index key 都包含相同 hash tag，删除时先读索引集合再批量删除。
 * Redis 仍然只是热缓存，任何失败都回退 openGauss 事实源。</p>
 */
@Component
@EnableConfigurationProperties(RuntimeBindingProperties.class)
public class RedisRuntimeBindingCache implements RuntimeBindingCache {
    private static final Logger log = LoggerFactory.getLogger(RedisRuntimeBindingCache.class);
    private static final String UNKNOWN = "_";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RuntimeBindingProperties properties;
    private final FinanceExRedisKeyNamespace keyNamespace;

    public RedisRuntimeBindingCache(StringRedisTemplate redis, ObjectMapper objectMapper,
                                    RuntimeBindingProperties properties,
                                    FinanceExRedisKeyNamespace keyNamespace) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.keyNamespace = keyNamespace;
    }

    @Override
    public Optional<RuntimeBinding> get(String tenantId, String userId, String sessionId, String leafMessageId) {
        try {
            String value = redis.opsForValue().get(key(tenantId, userId, sessionId, leafMessageId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, RuntimeBinding.class));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("RuntimeBinding Redis 读取失败，本轮回源 openGauss。原因：{}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<RuntimeBinding> get(String tenantId, String userId, String sessionId) {
        return get(tenantId, userId, sessionId, null);
    }

    @Override
    public void put(RuntimeBinding binding) {
        if (binding == null) {
            return;
        }
        try {
            String key = key(binding.tenantId(), binding.userId(), binding.chatSessionId(), binding.leafMessageId());
            String indexKey = indexKey(binding.tenantId(), binding.userId(), binding.chatSessionId());
            redis.opsForValue().set(key, objectMapper.writeValueAsString(binding), properties.getRedisTtl());
            redis.opsForSet().add(indexKey, key);
            redis.expire(indexKey, properties.getRedisTtl());
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("RuntimeBinding Redis 写入失败，openGauss 仍作为事实源。原因：{}", ex.getMessage());
        }
    }

    @Override
    public void evict(String tenantId, String userId, String sessionId) {
        try {
            String indexKey = indexKey(tenantId, userId, sessionId);
            Set<String> keys = redis.opsForSet().members(indexKey);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
            redis.delete(indexKey);
        } catch (RuntimeException ex) {
            log.warn("RuntimeBinding Redis 删除失败。原因：{}", ex.getMessage());
        }
    }

    private String key(String tenantId, String userId, String sessionId, String leafMessageId) {
        return keyNamespace.prefix(properties.getRedisKeyPrefix())
                + ":"
                + sessionHashTag(tenantId, userId, sessionId)
                + ":"
                + normalize(leafMessageId);
    }

    private String indexKey(String tenantId, String userId, String sessionId) {
        return keyNamespace.prefix(properties.getRedisKeyPrefix()) + ":index:" + sessionHashTag(tenantId, userId, sessionId);
    }

    private String sessionHashTag(String tenantId, String userId, String sessionId) {
        return "{" + normalize(tenantId) + ":" + normalize(userId) + ":" + normalize(sessionId) + "}";
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
