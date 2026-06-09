package com.huawei.finance.front.one.application.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 聊天事件消费游标配置。
 *
 * <p>配置放在应用层，供 read cursor 应用服务和 Redis 缓存适配器共同使用，避免应用层反向依赖
 * infrastructure 包。</p>
 */
@ConfigurationProperties(prefix = "financeex.chat-read-cursor")
public class ChatReadCursorProperties {
    /** read cursor Redis 逻辑 key 前缀，必须以 fin_ex 开头；运行时会自动插入环境段。 */
    private String redisKeyPrefix = "fin_ex:chat_read_cursor";
    /** read cursor Redis 热缓存 TTL。 */
    private Duration redisTtl = Duration.ofDays(7);
    /** ack 后写入 openGauss 的最小间隔；Redis 每次 ack 都刷新。 */
    private Duration databaseFlushInterval = Duration.ofSeconds(5);

    public String getRedisKeyPrefix() {
        return redisKeyPrefix;
    }

    public void setRedisKeyPrefix(String redisKeyPrefix) {
        this.redisKeyPrefix = redisKeyPrefix;
    }

    public Duration getRedisTtl() {
        return redisTtl;
    }

    public void setRedisTtl(Duration redisTtl) {
        this.redisTtl = redisTtl;
    }

    public Duration getDatabaseFlushInterval() {
        return databaseFlushInterval;
    }

    public void setDatabaseFlushInterval(Duration databaseFlushInterval) {
        this.databaseFlushInterval = databaseFlushInterval;
    }
}
