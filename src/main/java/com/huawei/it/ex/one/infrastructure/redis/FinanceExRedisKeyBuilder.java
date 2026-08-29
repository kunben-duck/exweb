package com.huawei.it.ex.one.infrastructure.redis;

import com.huawei.it.ex.one.infrastructure.memory.ShortTermMemoryRedisProperties;
import com.huawei.it.ex.one.infrastructure.persistence.ChatLiveEventBusProperties;
import com.huawei.it.ex.one.infrastructure.persistence.ChatRunCacheProperties;
import com.huawei.it.ex.one.infrastructure.runtime.RuntimeBindingProperties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * FinanceEX Redis key 构建器。
 *
 * <p>Redis key/channel 的环境隔离、业务维度拼接和 Redis Cluster hash tag 全部收敛在这里。
 * 业务适配器只调用具名方法，不直接拼接 {@code fin_ex:{env}:...}，避免漏加环境段或破坏
 * RuntimeBinding 同 slot 设计。</p>
 */
@Component
@EnableConfigurationProperties({
        RuntimeBindingProperties.class,
        ChatRunCacheProperties.class,
        ChatLiveEventBusProperties.class
})
public class FinanceExRedisKeyBuilder {
    private static final String ROOT_PREFIX = "fin_ex";
    private static final String DEFAULT_ENV = "default";
    private static final String UNKNOWN = "_";

    private final String env;
    private final RuntimeBindingProperties runtimeBindingProperties;
    private final ChatRunCacheProperties chatRunCacheProperties;
    private final ChatLiveEventBusProperties liveEventBusProperties;
    private final ShortTermMemoryRedisProperties shortTermMemoryProperties;

    @Autowired
    public FinanceExRedisKeyBuilder(Environment environment,
                                    RuntimeBindingProperties runtimeBindingProperties,
                                    ChatRunCacheProperties chatRunCacheProperties,
                                    ChatLiveEventBusProperties liveEventBusProperties,
                                    ShortTermMemoryRedisProperties shortTermMemoryProperties) {
        this(resolveEnv(environment), runtimeBindingProperties, chatRunCacheProperties,
                liveEventBusProperties, shortTermMemoryProperties);
    }

    private FinanceExRedisKeyBuilder(String env,
                                     RuntimeBindingProperties runtimeBindingProperties,
                                     ChatRunCacheProperties chatRunCacheProperties,
                                     ChatLiveEventBusProperties liveEventBusProperties,
                                     ShortTermMemoryRedisProperties shortTermMemoryProperties) {
        this.env = normalizeEnv(env);
        this.runtimeBindingProperties = runtimeBindingProperties;
        this.chatRunCacheProperties = chatRunCacheProperties;
        this.liveEventBusProperties = liveEventBusProperties;
        this.shortTermMemoryProperties = shortTermMemoryProperties;
    }

    /**
     * 创建指定环境的 key builder，主要用于单元测试。
     *
     * @param env 环境标识。
     * @return Redis key builder。
     */
    public static FinanceExRedisKeyBuilder ofEnv(String env) {
        return new FinanceExRedisKeyBuilder(env, new RuntimeBindingProperties(), new ChatRunCacheProperties(),
                new ChatLiveEventBusProperties(), new ShortTermMemoryRedisProperties());
    }

    /**
     * @return 当前 Redis key 使用的环境标识。
     */
    public String env() {
        return env;
    }

    /**
     * @return RuntimeBinding value key。该 key 与索引 key 共享同一个 hash tag，保证 Redis Cluster 同 slot 删除。
     */
    public String runtimeBinding(String tenantId, String userId, String sessionId, String leafMessageId) {
        return prefix(runtimeBindingProperties.getRedisKeyPrefix())
                + ":"
                + sessionHashTag(tenantId, userId, sessionId)
                + ":"
                + normalize(leafMessageId);
    }

    /**
     * @return RuntimeBinding 会话索引 key，用于避免 Redis Cluster 下使用 KEYS 扫描。
     */
    public String runtimeBindingIndex(String tenantId, String userId, String sessionId) {
        return prefix(runtimeBindingProperties.getRedisKeyPrefix())
                + ":index:"
                + sessionHashTag(tenantId, userId, sessionId);
    }

    /**
     * @return 当前会话 active run 热缓存 key。
     */
    public String activeRun(String tenantId, String userId, String sessionId) {
        return prefix(chatRunCacheProperties.getActiveKeyPrefix())
                + ":" + normalize(tenantId)
                + ":" + normalize(userId)
                + ":" + normalize(sessionId);
    }

    /**
     * @return run 取消标记 key。
     */
    public String cancelFlag(String runId) {
        return prefix(chatRunCacheProperties.getCancelKeyPrefix()) + ":" + normalize(runId);
    }

    /**
     * @return stale run 恢复抢占优化锁 key。
     */
    public String recoverLock(String runId) {
        return prefix(chatRunCacheProperties.getRecoverLockKeyPrefix()) + ":" + normalize(runId);
    }

    /**
     * @return 短期记忆最近消息列表 key。
     */
    public String shortTermMemoryMessages(String tenantId, String userId, String sessionId) {
        return prefix(shortTermMemoryProperties.getRedisKeyPrefix())
                + ":messages:"
                + normalize(tenantId)
                + ":"
                + normalize(userId)
                + ":"
                + normalize(sessionId);
    }

    /**
     * @return DomainAgent assistant 留存策略缓存 key。
     */
    public String agentDataPersistencePolicy(
            String logicalPrefix,
            String tenantId,
            String runtimeProvider,
            String skillId) {
        return prefix(logicalPrefix)
                + ":"
                + normalize(tenantId)
                + ":"
                + normalize(runtimeProvider)
                + ":"
                + normalize(skillId);
    }

    /**
     * @return DomainAgent完整技能配置缓存key。
     */
    public String domainAgentSkillConfiguration(
            String logicalPrefix,
            String tenantId,
            String skillId) {
        return prefix(logicalPrefix)
                + ":"
                + normalize(tenantId)
                + ":"
                + normalize(skillId);
    }

    /**
     * @return WebSocket run topic 对应的 Redis Pub/Sub channel。
     */
    public String chatStreamChannel(String streamTopicId) {
        return prefix(liveEventBusProperties.getRedisChannelPrefix()) + ":" + normalize(streamTopicId);
    }

    /**
     * 从 Redis Pub/Sub channel 反解 stream topic id。
     *
     * @param channel Redis channel。
     * @return 当前环境下可识别的 topic id；无法识别时原样返回，便于日志排障。
     */
    public String topicFromChatStreamChannel(String channel) {
        String prefix = prefix(liveEventBusProperties.getRedisChannelPrefix()) + ":";
        return channel != null && channel.startsWith(prefix) ? channel.substring(prefix.length()) : channel;
    }

    private String prefix(String logicalPrefix) {
        if (logicalPrefix == null || logicalPrefix.isBlank()) {
            throw new IllegalArgumentException("Redis key prefix 不能为空");
        }
        String trimmed = logicalPrefix.trim();
        if (!ROOT_PREFIX.equals(trimmed) && !trimmed.startsWith(ROOT_PREFIX + ":")) {
            throw new IllegalArgumentException("Redis key prefix 必须以 fin_ex 开头: " + logicalPrefix);
        }
        return ROOT_PREFIX + ":" + env + trimmed.substring(ROOT_PREFIX.length());
    }

    private String sessionHashTag(String tenantId, String userId, String sessionId) {
        return "{" + normalize(tenantId) + ":" + normalize(userId) + ":" + normalize(sessionId) + "}";
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }

    private static String resolveEnv(Environment environment) {
        if (environment == null || environment.getActiveProfiles().length == 0) {
            return DEFAULT_ENV;
        }
        return environment.getActiveProfiles()[0];
    }

    private static String normalizeEnv(String value) {
        String text = value == null || value.isBlank() ? DEFAULT_ENV : value.trim().toLowerCase(Locale.ROOT);
        text = text.replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return text.isBlank() ? DEFAULT_ENV : text;
    }
}
