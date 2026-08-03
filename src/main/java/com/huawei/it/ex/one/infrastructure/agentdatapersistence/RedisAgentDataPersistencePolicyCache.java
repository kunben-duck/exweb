package com.huawei.it.ex.one.infrastructure.agentdatapersistence;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.integration.agentdatapersistence.AgentDataPersistencePolicyCache;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** Redis 跨实例 assistant 留存策略缓存。 */
@Component
public class RedisAgentDataPersistencePolicyCache implements AgentDataPersistencePolicyCache {
    private final StringRedisTemplate redis;
    private final FinanceExRedisKeyBuilder redisKeys;
    private final AgentDataPersistenceProperties properties;

    public RedisAgentDataPersistencePolicyCache(
            StringRedisTemplate redis,
            FinanceExRedisKeyBuilder redisKeys,
            AgentDataPersistenceProperties properties) {
        this.redis = redis;
        this.redisKeys = redisKeys;
        this.properties = properties;
    }

    @Override
    public Optional<AgentDataPersistencePolicy> get(
            String tenantId,
            String runtimeProvider,
            String skillId) {
        String value = redis.opsForValue().get(key(tenantId, runtimeProvider, skillId));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(AgentDataPersistencePolicy.valueOf(value.trim()));
        } catch (IllegalArgumentException ex) {
            redis.delete(key(tenantId, runtimeProvider, skillId));
            return Optional.empty();
        }
    }

    @Override
    public void put(
            String tenantId,
            String runtimeProvider,
            String skillId,
            AgentDataPersistencePolicy policy,
            Duration ttl) {
        if (policy == null) {
            return;
        }
        redis.opsForValue().set(key(tenantId, runtimeProvider, skillId), policy.name(), ttl);
    }

    private String key(String tenantId, String runtimeProvider, String skillId) {
        return redisKeys.agentDataPersistencePolicy(
                properties.normalizedCacheKeyPrefix(), tenantId, runtimeProvider, skillId);
    }
}
