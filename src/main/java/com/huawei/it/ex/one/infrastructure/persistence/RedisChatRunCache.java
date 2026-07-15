package com.huawei.it.ex.one.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunCache;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRecoverLock;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunCancelSignal;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;
import java.time.Duration;
import java.util.Optional;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * ChatRun Redis 热缓存实现。
 *
 * <p>Redis 不承担事实源职责；任何 Redis 失败都只会让上层回源数据库或退化为本 JVM 取消。</p>
 */
@Component
@EnableConfigurationProperties(ChatRunCacheProperties.class)
public class RedisChatRunCache implements ChatRunCache, ChatRunRecoverLock {
    private static final AppLogger log = AppLoggerFactory.getLogger(RedisChatRunCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ChatRunCacheProperties properties;
    private final FinanceExRedisKeyBuilder redisKeys;

    public RedisChatRunCache(StringRedisTemplate redis, ObjectMapper objectMapper, ChatRunCacheProperties properties,
                             FinanceExRedisKeyBuilder redisKeys) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.redisKeys = redisKeys;
    }

    @Override
    public Optional<ChatRun> getActive(String tenantId, String userId, String sessionId) {
        try {
            String value = redis.opsForValue().get(redisKeys.activeRun(tenantId, userId, sessionId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, ChatRun.class));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("ChatRun active Redis 读取失败，本轮回源数据库。原因：{}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean tryClaimActive(ChatRun run) {
        if (run == null) {
            return false;
        }
        try {
            Boolean claimed = redis.opsForValue().setIfAbsent(
                    redisKeys.activeRun(run.tenantId(), run.userId(), run.sessionId()),
                    objectMapper.writeValueAsString(run),
                    properties.getActiveTtl()
            );
            return Boolean.TRUE.equals(claimed);
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("ChatRun active Redis 原子声明失败，未获得缓存声明。数据库唯一索引仍是 active run 事实栅栏。原因：{}",
                    ex.getMessage());
            return false;
        }
    }

    @Override
    public void putActive(ChatRun run) {
        if (run == null) {
            return;
        }
        try {
            redis.opsForValue().set(redisKeys.activeRun(run.tenantId(), run.userId(), run.sessionId()),
                    objectMapper.writeValueAsString(run), properties.getActiveTtl());
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("ChatRun active Redis 写入失败，数据库仍作为事实源。原因：{}", ex.getMessage());
        }
    }

    @Override
    public void evictActive(String tenantId, String userId, String sessionId) {
        try {
            redis.delete(redisKeys.activeRun(tenantId, userId, sessionId));
        } catch (RuntimeException ex) {
            log.warn("ChatRun active Redis 删除失败。原因：{}", ex.getMessage());
        }
    }

    @Override
    public void markCancellationRequested(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().set(redisKeys.cancelFlag(runId), "1", properties.getCancelTtl());
        } catch (RuntimeException ex) {
            log.warn("ChatRun cancel Redis 写入失败，将依赖数据库 CANCELLING 状态兜底阻断迟到事件。原因：{}", ex.getMessage());
        }
    }

    @Override
    public ChatRunCancelSignal cancellationSignal(String runId) {
        if (runId == null || runId.isBlank()) {
            return ChatRunCancelSignal.NOT_REQUESTED;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(redisKeys.cancelFlag(runId)))
                    ? ChatRunCancelSignal.REQUESTED
                    : ChatRunCancelSignal.NOT_REQUESTED;
        } catch (RuntimeException ex) {
            log.warn("ChatRun cancel Redis 读取失败。原因：{}", ex.getMessage());
            return ChatRunCancelSignal.UNKNOWN;
        }
    }

    @Override
    public boolean tryLock(String runId, String ownerInstanceId, Duration ttl) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        try {
            Boolean locked = redis.opsForValue().setIfAbsent(
                    redisKeys.recoverLock(runId),
                    ownerInstanceId == null || ownerInstanceId.isBlank() ? "_" : ownerInstanceId,
                    ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofSeconds(30) : ttl
            );
            return Boolean.TRUE.equals(locked);
        } catch (RuntimeException ex) {
            log.warn("ChatRun recover Redis 锁获取失败，将继续依赖数据库条件抢占。runId={}, reason={}",
                    runId, ex.getMessage());
            return true;
        }
    }

}
