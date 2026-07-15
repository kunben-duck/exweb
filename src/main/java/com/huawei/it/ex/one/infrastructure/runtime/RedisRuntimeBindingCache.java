package com.huawei.it.ex.one.infrastructure.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;
import java.util.Optional;
import java.util.Set;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * RuntimeBinding Redis 热缓存实现。
 *
 * <p>Redis Cluster 下禁止使用 {@code KEYS} 做会话级模糊删除，因此每个会话维护一个同 slot
 * 的索引集合：binding key 与 index key 都包含相同 hash tag，删除时先读索引集合再批量删除。
 * Redis 仍然只是热缓存，任何失败都回退数据库事实源。</p>
 */
@Component
@EnableConfigurationProperties(RuntimeBindingProperties.class)
public class RedisRuntimeBindingCache implements RuntimeBindingCache {
    private static final AppLogger log = AppLoggerFactory.getLogger(RedisRuntimeBindingCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RuntimeBindingProperties properties;
    private final FinanceExRedisKeyBuilder redisKeys;

    public RedisRuntimeBindingCache(StringRedisTemplate redis, ObjectMapper objectMapper,
                                    RuntimeBindingProperties properties,
                                    FinanceExRedisKeyBuilder redisKeys) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.redisKeys = redisKeys;
    }

    @Override
    public Optional<RuntimeBinding> get(String tenantId, String userId, String sessionId, String leafMessageId) {
        try {
            String value = redis.opsForValue().get(redisKeys.runtimeBinding(tenantId, userId, sessionId, leafMessageId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, RuntimeBinding.class));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("RuntimeBinding Redis 读取失败，本轮回源数据库。原因：{}", ex.getMessage());
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
            String key = redisKeys.runtimeBinding(binding.tenantId(), binding.userId(),
                    binding.chatSessionId(), binding.leafMessageId());
            String indexKey = redisKeys.runtimeBindingIndex(binding.tenantId(), binding.userId(),
                    binding.chatSessionId());
            redis.opsForValue().set(key, objectMapper.writeValueAsString(binding), properties.getRedisTtl());
            redis.opsForSet().add(indexKey, key);
            redis.expire(indexKey, properties.getRedisTtl());
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("RuntimeBinding Redis 写入失败，数据库仍作为事实源。原因：{}", ex.getMessage());
        }
    }

    @Override
    public void evict(String tenantId, String userId, String sessionId) {
        try {
            String indexKey = redisKeys.runtimeBindingIndex(tenantId, userId, sessionId);
            Set<String> keys = redis.opsForSet().members(indexKey);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
            redis.delete(indexKey);
        } catch (RuntimeException ex) {
            log.warn("RuntimeBinding Redis 删除失败。原因：{}", ex.getMessage());
        }
    }

}
