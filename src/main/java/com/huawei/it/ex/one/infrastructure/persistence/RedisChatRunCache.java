package com.huawei.it.ex.one.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunCache;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRecoverLock;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
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
            SystemErrorCode code = ex instanceof JsonProcessingException
                    ? SystemErrorCode.REDIS_DESERIALIZATION_FAILED
                    : SystemErrorCode.REDIS_READ_FAILED;
            log.warn(SystemErrorLogEntry.builder(code,
                            "Active ChatRun Redis read failed; falling back to database")
                    .operation("chat-run.cache.read")
                    .sessionId(sessionId)
                    .build(), ex);
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
            SystemErrorCode code = ex instanceof JsonProcessingException
                    ? SystemErrorCode.REDIS_SERIALIZATION_FAILED
                    : SystemErrorCode.REDIS_WRITE_FAILED;
            log.warn(SystemErrorLogEntry.builder(code,
                            "Active ChatRun Redis claim failed; database admission guard remains authoritative")
                    .operation("chat-run.cache.claim")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .build(), ex);
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
            SystemErrorCode code = ex instanceof JsonProcessingException
                    ? SystemErrorCode.REDIS_SERIALIZATION_FAILED
                    : SystemErrorCode.REDIS_WRITE_FAILED;
            log.warn(SystemErrorLogEntry.builder(code,
                            "Active ChatRun Redis write failed; database remains authoritative")
                    .operation("chat-run.cache.write")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .build(), ex);
        }
    }

    @Override
    public void evictActive(String tenantId, String userId, String sessionId) {
        try {
            redis.delete(redisKeys.activeRun(tenantId, userId, sessionId));
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_WRITE_FAILED,
                            "Active ChatRun Redis eviction failed")
                    .operation("chat-run.cache.evict")
                    .sessionId(sessionId)
                    .build(), ex);
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
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_WRITE_FAILED,
                            "ChatRun cancellation cache write failed; database state remains authoritative")
                    .operation("chat-run.cancel-cache.write")
                    .runId(runId)
                    .build(), ex);
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
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_READ_FAILED,
                            "ChatRun cancellation cache read failed")
                    .operation("chat-run.cancel-cache.read")
                    .runId(runId)
                    .build(), ex);
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
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_LOCK_FAILED,
                            "ChatRun recovery Redis lock failed; relying on database fencing")
                    .operation("chat-run.recovery-lock.acquire")
                    .runId(runId)
                    .build(), ex);
            return true;
        }
    }

}
