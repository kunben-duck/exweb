package com.huawei.finance.front.one.application.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 聊天事件流降压配置。
 *
 * <p>这些配置只改变服务端内部把下游 token 合并成 {@code message.delta} event 的粒度，
 * 不改变前端协议、run topic、Event Resume 或数据库 seq 游标语义。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.chat-stream")
public class ChatStreamProperties {
    /** 是否合并连续 message.delta，默认开启以降低数据库与 Redis 写放大。 */
    private boolean deltaCoalesceEnabled = true;
    /** 连续 delta 最大等待合并窗口；窗口到期后会立即 flush 为一个标准 message.delta event。 */
    private Duration deltaCoalesceWindow = Duration.ofMillis(50);
    /** 单个合并后 delta 的最大字符数；超过该值立即 flush，避免前端首屏等待过久。 */
    private int deltaCoalesceMaxChars = 512;
    /** turn stream heartbeat 间隔；用于 WebSocket/Event Resume 在长时间无业务事件时维持连接活跃。 */
    private Duration turnHeartbeatInterval = Duration.ofSeconds(15);
    /** run 级 Event Resume 的 live tail 故障后，按该间隔回查数据库直到 run 终态。 */
    private Duration resumePollInterval = Duration.ofSeconds(1);
    /** 流式事件落库、run 状态推进和实时发布使用的阻塞 IO 线程数上限。 */
    private int eventIoExecutorMaxSize = 16;
    /** 流式事件 IO 线程池队列容量；队列满时上游 run 会失败而不是阻塞 Reactor timer/Servlet 线程。 */
    private int eventIoExecutorQueueCapacity = 10_000;

    public boolean isDeltaCoalesceEnabled() {
        return deltaCoalesceEnabled;
    }

    public void setDeltaCoalesceEnabled(boolean deltaCoalesceEnabled) {
        this.deltaCoalesceEnabled = deltaCoalesceEnabled;
    }

    public Duration getDeltaCoalesceWindow() {
        return deltaCoalesceWindow;
    }

    public void setDeltaCoalesceWindow(Duration deltaCoalesceWindow) {
        this.deltaCoalesceWindow = deltaCoalesceWindow;
    }

    public int getDeltaCoalesceMaxChars() {
        return deltaCoalesceMaxChars;
    }

    public void setDeltaCoalesceMaxChars(int deltaCoalesceMaxChars) {
        this.deltaCoalesceMaxChars = deltaCoalesceMaxChars;
    }

    public Duration normalizedDeltaCoalesceWindow() {
        if (deltaCoalesceWindow == null || deltaCoalesceWindow.isNegative()) {
            return Duration.ofMillis(50);
        }
        return deltaCoalesceWindow;
    }

    public int normalizedDeltaCoalesceMaxChars() {
        return Math.max(1, deltaCoalesceMaxChars);
    }

    public Duration getTurnHeartbeatInterval() {
        return turnHeartbeatInterval;
    }

    public void setTurnHeartbeatInterval(Duration turnHeartbeatInterval) {
        this.turnHeartbeatInterval = turnHeartbeatInterval;
    }

    public Duration normalizedTurnHeartbeatInterval() {
        if (turnHeartbeatInterval == null || turnHeartbeatInterval.isNegative()) {
            return Duration.ofSeconds(15);
        }
        return turnHeartbeatInterval;
    }

    public Duration getResumePollInterval() {
        return resumePollInterval;
    }

    public void setResumePollInterval(Duration resumePollInterval) {
        this.resumePollInterval = resumePollInterval;
    }

    public Duration normalizedResumePollInterval() {
        if (resumePollInterval == null || resumePollInterval.isNegative()) {
            return Duration.ofSeconds(1);
        }
        return resumePollInterval.isZero() ? Duration.ofSeconds(1) : resumePollInterval;
    }

    public int getEventIoExecutorMaxSize() {
        return eventIoExecutorMaxSize;
    }

    public void setEventIoExecutorMaxSize(int eventIoExecutorMaxSize) {
        this.eventIoExecutorMaxSize = eventIoExecutorMaxSize;
    }

    public int getEventIoExecutorQueueCapacity() {
        return eventIoExecutorQueueCapacity;
    }

    public void setEventIoExecutorQueueCapacity(int eventIoExecutorQueueCapacity) {
        this.eventIoExecutorQueueCapacity = eventIoExecutorQueueCapacity;
    }

    public int normalizedEventIoExecutorMaxSize() {
        return Math.max(1, eventIoExecutorMaxSize);
    }

    public int normalizedEventIoExecutorQueueCapacity() {
        return Math.max(128, eventIoExecutorQueueCapacity);
    }
}
