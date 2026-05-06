package com.huawei.finance.front.one.infrastructure.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.task.TaskCardCache;
import com.huawei.finance.front.one.domain.task.TaskCard;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * TaskCard Redis 热缓存实现。
 *
 * <p>Redis 只承载热路径和 TTL 控制：active key 命中时可直接进入 ContinuationGuard；
 * Redis miss 时必须回源 openGauss。任何 Redis 读写失败都不能改变 openGauss 事实状态。</p>
 */
@Component
@EnableConfigurationProperties(TaskCardRedisProperties.class)
public class RedisTaskCardCache implements TaskCardCache {
    private static final Logger log = LoggerFactory.getLogger(RedisTaskCardCache.class);
    private static final String UNKNOWN = "_";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final TaskCardRedisProperties properties;

    public RedisTaskCardCache(StringRedisTemplate redis, ObjectMapper objectMapper, TaskCardRedisProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<TaskCard> getActive(String tenantId, String userId, String sessionId) {
        try {
            String value = redis.opsForValue().get(activeKey(tenantId, userId, sessionId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, TaskCard.class));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("TaskCard Redis 读取失败，本轮回源 openGauss。原因：{}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(TaskCard taskCard) {
        if (taskCard == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(taskCard);
            redis.opsForValue().set(cardKey(taskCard), json, properties.getTtl());
            if (taskCard.activeAt(Instant.now())) {
                redis.opsForValue().set(activeKey(taskCard.tenantId(), taskCard.userId(), taskCard.chatSessionId()), json, properties.getTtl());
            } else {
                redis.delete(activeKey(taskCard.tenantId(), taskCard.userId(), taskCard.chatSessionId()));
            }
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("TaskCard Redis 写入失败，openGauss 仍作为事实源。原因：{}", ex.getMessage());
        }
    }

    @Override
    public void evictActive(String tenantId, String userId, String sessionId) {
        try {
            redis.delete(activeKey(tenantId, userId, sessionId));
        } catch (RuntimeException ex) {
            log.warn("TaskCard Redis 删除失败。原因：{}", ex.getMessage());
        }
    }

    private String activeKey(String tenantId, String userId, String sessionId) {
        return properties.getActiveKeyPrefix()
                + ":"
                + normalize(tenantId)
                + ":"
                + normalize(userId)
                + ":"
                + normalize(sessionId);
    }

    private String cardKey(TaskCard taskCard) {
        return properties.getCardKeyPrefix()
                + ":"
                + normalize(taskCard.tenantId())
                + ":"
                + normalize(taskCard.userId())
                + ":"
                + normalize(taskCard.chatSessionId())
                + ":"
                + normalize(taskCard.taskId());
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
