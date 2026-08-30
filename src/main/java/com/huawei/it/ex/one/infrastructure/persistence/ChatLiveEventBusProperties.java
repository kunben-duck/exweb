/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * WebSocket 跨实例实时事件总线配置。
 */
@ConfigurationProperties(prefix = "financeex.websocket")
public class ChatLiveEventBusProperties {
    /** Redis Pub/Sub 逻辑 channel 前缀，必须以 fin_ex 开头；运行时会自动插入环境段。 */
    private String redisChannelPrefix = "fin_ex:chat_stream";
    /** Redis 发布后台执行器核心线程数。 */
    private int redisPublishExecutorCoreSize = 2;
    /** Redis 发布后台执行器最大线程数。 */
    private int redisPublishExecutorMaxSize = 8;
    /** Redis 发布后台执行器全局队列容量。 */
    private int redisPublishQueueCapacity = 4096;
    /** 单个 topic 待发布事件队列容量。 */
    private int redisPublishTopicQueueSize = 1024;
    /** 单个 topic 待发布事件队列累计 payload 字节上限。 */
    private DataSize redisPublishTopicMaxBytes = DataSize.ofMegabytes(8);
    /** Redis publish 短重试次数；0 表示只尝试一次。 */
    private int redisPublishRetryAttempts = 2;
    /** Redis publish 短重试退避间隔。 */
    private Duration redisPublishRetryBackoff = Duration.ofMillis(20);
    /** topic degraded 后，恢复控制消息的后台重试间隔。 */
    private Duration redisPublishRecoveryRetryInterval = Duration.ofSeconds(1);

    public String getRedisChannelPrefix() {
        return redisChannelPrefix;
    }

    public void setRedisChannelPrefix(String redisChannelPrefix) {
        this.redisChannelPrefix = redisChannelPrefix;
    }

    public int getRedisPublishExecutorCoreSize() {
        return redisPublishExecutorCoreSize;
    }

    public void setRedisPublishExecutorCoreSize(int redisPublishExecutorCoreSize) {
        this.redisPublishExecutorCoreSize = redisPublishExecutorCoreSize;
    }

    public int getRedisPublishExecutorMaxSize() {
        return redisPublishExecutorMaxSize;
    }

    public void setRedisPublishExecutorMaxSize(int redisPublishExecutorMaxSize) {
        this.redisPublishExecutorMaxSize = redisPublishExecutorMaxSize;
    }

    public int getRedisPublishQueueCapacity() {
        return redisPublishQueueCapacity;
    }

    public void setRedisPublishQueueCapacity(int redisPublishQueueCapacity) {
        this.redisPublishQueueCapacity = redisPublishQueueCapacity;
    }

    public int getRedisPublishTopicQueueSize() {
        return redisPublishTopicQueueSize;
    }

    public void setRedisPublishTopicQueueSize(int redisPublishTopicQueueSize) {
        this.redisPublishTopicQueueSize = redisPublishTopicQueueSize;
    }

    public DataSize getRedisPublishTopicMaxBytes() {
        return redisPublishTopicMaxBytes;
    }

    public void setRedisPublishTopicMaxBytes(DataSize redisPublishTopicMaxBytes) {
        this.redisPublishTopicMaxBytes = redisPublishTopicMaxBytes;
    }

    public int getRedisPublishRetryAttempts() {
        return redisPublishRetryAttempts;
    }

    public void setRedisPublishRetryAttempts(int redisPublishRetryAttempts) {
        this.redisPublishRetryAttempts = redisPublishRetryAttempts;
    }

    public Duration getRedisPublishRetryBackoff() {
        return redisPublishRetryBackoff;
    }

    public void setRedisPublishRetryBackoff(Duration redisPublishRetryBackoff) {
        this.redisPublishRetryBackoff = redisPublishRetryBackoff;
    }

    public Duration getRedisPublishRecoveryRetryInterval() {
        return redisPublishRecoveryRetryInterval;
    }

    public void setRedisPublishRecoveryRetryInterval(Duration redisPublishRecoveryRetryInterval) {
        this.redisPublishRecoveryRetryInterval = redisPublishRecoveryRetryInterval;
    }

    public int normalizedRedisPublishExecutorCoreSize() {
        return Math.max(1, redisPublishExecutorCoreSize);
    }

    public int normalizedRedisPublishExecutorMaxSize() {
        return Math.max(normalizedRedisPublishExecutorCoreSize(), redisPublishExecutorMaxSize);
    }

    public int normalizedRedisPublishQueueCapacity() {
        return Math.max(128, redisPublishQueueCapacity);
    }

    public int normalizedRedisPublishTopicQueueSize() {
        return Math.max(16, redisPublishTopicQueueSize);
    }

    public long normalizedRedisPublishTopicMaxBytes() {
        DataSize normalized = redisPublishTopicMaxBytes == null ? DataSize.ofMegabytes(8) : redisPublishTopicMaxBytes;
        return Math.max(64 * 1024L, normalized.toBytes());
    }

    public int normalizedRedisPublishRetryAttempts() {
        return Math.max(0, redisPublishRetryAttempts);
    }

    public Duration normalizedRedisPublishRetryBackoff() {
        if (redisPublishRetryBackoff == null || redisPublishRetryBackoff.isNegative()) {
            return Duration.ofMillis(20);
        }
        return redisPublishRetryBackoff;
    }

    public Duration normalizedRedisPublishRecoveryRetryInterval() {
        if (redisPublishRecoveryRetryInterval == null || redisPublishRecoveryRetryInterval.isNegative()) {
            return Duration.ofSeconds(1);
        }
        return redisPublishRecoveryRetryInterval.isZero() ? Duration.ofSeconds(1) : redisPublishRecoveryRetryInterval;
    }
}
