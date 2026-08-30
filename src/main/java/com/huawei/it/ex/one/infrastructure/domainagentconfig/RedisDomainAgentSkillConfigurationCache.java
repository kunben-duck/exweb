/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationCache;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** Redis跨实例DomainAgent完整技能配置缓存。 */
@Component
public class RedisDomainAgentSkillConfigurationCache implements DomainAgentSkillConfigurationCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FinanceExRedisKeyBuilder redisKeys;
    private final DomainAgentSkillConfigurationProperties properties;

    public RedisDomainAgentSkillConfigurationCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            FinanceExRedisKeyBuilder redisKeys,
            DomainAgentSkillConfigurationProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.redisKeys = redisKeys;
        this.properties = properties;
    }

    @Override
    public Optional<DomainAgentSkillConfiguration> get(String tenantId, String skillId) {
        String cacheKey = key(tenantId, skillId);
        String value = redis.opsForValue().get(cacheKey);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            DomainAgentSkillConfiguration configuration = objectMapper.readValue(
                    value, DomainAgentSkillConfiguration.class);
            if (configuration.skillId() == null
                    || !skillId.equals(configuration.skillId().trim())) {
                redis.delete(cacheKey);
                return Optional.empty();
            }
            return Optional.of(configuration);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            redis.delete(cacheKey);
            return Optional.empty();
        }
    }

    @Override
    public void put(
            String tenantId,
            String skillId,
            DomainAgentSkillConfiguration configuration,
            Duration ttl) {
        if (configuration == null) {
            return;
        }
        try {
            redis.opsForValue().set(key(tenantId, skillId), objectMapper.writeValueAsString(configuration), ttl);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("DomainAgent skill configuration cache serialization failed", ex);
        }
    }

    private String key(String tenantId, String skillId) {
        return redisKeys.domainAgentSkillConfiguration(
                properties.normalizedCacheKeyPrefix(), tenantId, skillId);
    }
}
