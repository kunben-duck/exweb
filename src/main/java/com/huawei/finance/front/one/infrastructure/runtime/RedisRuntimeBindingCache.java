package com.huawei.finance.front.one.infrastructure.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * RuntimeBinding Redis 热缓存实现。
 */
@Component
@EnableConfigurationProperties(RuntimeBindingProperties.class)
public class RedisRuntimeBindingCache implements RuntimeBindingCache {
    private static final Logger log = LoggerFactory.getLogger(RedisRuntimeBindingCache.class);
    private static final String UNKNOWN = "_";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RuntimeBindingProperties properties;

    public RedisRuntimeBindingCache(StringRedisTemplate redis, ObjectMapper objectMapper,
                                    RuntimeBindingProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<RuntimeBinding> get(String tenantId, String userId, String sessionId) {
        try {
            String value = redis.opsForValue().get(key(tenantId, userId, sessionId));
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
    public void put(RuntimeBinding binding) {
        if (binding == null) {
            return;
        }
        try {
            redis.opsForValue().set(key(binding.tenantId(), binding.userId(), binding.chatSessionId()),
                    objectMapper.writeValueAsString(binding), properties.getRedisTtl());
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("RuntimeBinding Redis 写入失败，openGauss 仍作为事实源。原因：{}", ex.getMessage());
        }
    }

    @Override
    public void evict(String tenantId, String userId, String sessionId) {
        try {
            redis.delete(key(tenantId, userId, sessionId));
        } catch (RuntimeException ex) {
            log.warn("RuntimeBinding Redis 删除失败。原因：{}", ex.getMessage());
        }
    }

    private String key(String tenantId, String userId, String sessionId) {
        return properties.getRedisKeyPrefix()
                + ":"
                + normalize(tenantId)
                + ":"
                + normalize(userId)
                + ":"
                + normalize(sessionId);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
