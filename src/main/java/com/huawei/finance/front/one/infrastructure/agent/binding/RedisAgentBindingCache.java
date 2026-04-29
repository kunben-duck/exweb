package com.huawei.finance.front.one.infrastructure.agent.binding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.gateway.AgentBindingCache;
import com.huawei.finance.front.one.domain.agent.AgentBinding;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(AgentBindingProperties.class)
public class RedisAgentBindingCache implements AgentBindingCache {
    private static final Logger log = LoggerFactory.getLogger(RedisAgentBindingCache.class);
    private static final String UNKNOWN = "_";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AgentBindingProperties properties;

    public RedisAgentBindingCache(StringRedisTemplate redis, ObjectMapper objectMapper, AgentBindingProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<AgentBinding> get(String tenantId, String userId, String sessionId) {
        try {
            // Redis key 必须使用 fin_ex 前缀，遵循项目统一命名规范。
            // value 存完整 AgentBinding JSON，避免热路径再拼装字段。
            String value = redis.opsForValue().get(key(tenantId, userId, sessionId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, AgentBinding.class));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("AgentBinding Redis 读取失败，本轮回源 openGauss。原因：{}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(AgentBinding binding) {
        if (binding == null) {
            return;
        }
        try {
            // TTL 只控制热缓存生命周期；真实状态仍以 openGauss 为准。
            redis.opsForValue().set(key(binding.tenantId(), binding.userId(), binding.chatSessionId()),
                    objectMapper.writeValueAsString(binding), properties.getRedisTtl());
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("AgentBinding Redis 写入失败，openGauss 仍作为事实源。原因：{}", ex.getMessage());
        }
    }

    @Override
    public void evict(String tenantId, String userId, String sessionId) {
        try {
            redis.delete(key(tenantId, userId, sessionId));
        } catch (RuntimeException ex) {
            log.warn("AgentBinding Redis 删除失败。原因：{}", ex.getMessage());
        }
    }

    private String key(String tenantId, String userId, String sessionId) {
        // key 形态：fin_ex:agent_binding:{tenantId}:{userId}:{sessionId}
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
