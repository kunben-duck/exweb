package com.huawei.finance.front.one.infrastructure.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.gateway.WorkingMemoryStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 工作记忆 Redis 存储。
 *
 * <p>WorkingMemory 只保存轻量运行变量，例如最近一次 runId、前端临时状态或路由辅助标记。
 * 它不承担审计事实源职责，因此使用 Redis TTL 语义即可；会话消息、摘要和绑定仍分别落到 openGauss。</p>
 */
@Component
public class RedisWorkingMemoryStore implements WorkingMemoryStore {
    private static final Logger log = LoggerFactory.getLogger(RedisWorkingMemoryStore.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String UNKNOWN_SESSION = "_";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final WorkingMemoryRedisProperties properties;
    private volatile Instant retryAfter = Instant.MIN;

    public RedisWorkingMemoryStore(StringRedisTemplate redis, ObjectMapper objectMapper,
                                   WorkingMemoryRedisProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> load(String sessionId) {
        if (!canUseRedis()) {
            return Map.of();
        }
        try {
            String value = redis.opsForValue().get(key(sessionId));
            return value == null || value.isBlank() ? Map.of() : objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            log.warn("忽略无法反序列化的工作记忆，sessionId={}", sessionId);
            return Map.of();
        } catch (RuntimeException ex) {
            markRedisFailure(ex);
            return Map.of();
        }
    }

    @Override
    public void update(String sessionId, Map<String, Object> variables) {
        if (!canUseRedis() || sessionId == null || sessionId.isBlank() || variables == null || variables.isEmpty()) {
            return;
        }
        try {
            // update 是增量语义：先读取已有变量，再用本轮变量覆盖同名字段。
            Map<String, Object> merged = new LinkedHashMap<>(load(sessionId));
            merged.putAll(variables);
            redis.opsForValue().set(key(sessionId), objectMapper.writeValueAsString(merged), properties.getTtl());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("工作记忆序列化失败", ex);
        } catch (RuntimeException ex) {
            markRedisFailure(ex);
        }
    }

    @Override
    public void clear(String sessionId) {
        if (!canUseRedis() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            redis.delete(key(sessionId));
        } catch (RuntimeException ex) {
            markRedisFailure(ex);
        }
    }

    private boolean canUseRedis() {
        return properties.isEnabled() && !Instant.now().isBefore(retryAfter);
    }

    private void markRedisFailure(RuntimeException ex) {
        if (isRedisConnectionProblem(ex)) {
            retryAfter = Instant.now().plus(properties.getFailureBackoff());
            log.warn("工作记忆 Redis 暂不可用，{} 后重试；本次请求按空工作记忆处理。", properties.getFailureBackoff());
            return;
        }
        throw ex;
    }

    private boolean isRedisConnectionProblem(RuntimeException ex) {
        return ex instanceof RedisConnectionFailureException
                || ex instanceof RedisSystemException
                || ex.getCause() instanceof RedisConnectionFailureException;
    }

    private String key(String sessionId) {
        return properties.getKeyPrefix() + ":variables:" + normalize(sessionId);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? UNKNOWN_SESSION : value;
    }
}
