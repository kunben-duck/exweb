package com.huawei.finance.front.one.application.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 聊天事件流传输配置。
 *
 * <p>当前生产版本不再合并 {@code message.delta}。历史合并配置字段保留为兼容入口，
 * 后续只有在实现 demand-aware 合并器并完成压测后才会重新生效。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.chat-stream")
public class ChatStreamProperties {
    /** 历史 delta 合并开关；当前止血版本忽略该配置，事件按原粒度写入和推送。 */
    private boolean deltaCoalesceEnabled = false;
    /** 历史 delta 合并窗口；当前止血版本暂不生效。 */
    private Duration deltaCoalesceWindow = Duration.ofMillis(50);
    /** 历史 delta 合并最大字符数；当前止血版本暂不生效。 */
    private int deltaCoalesceMaxChars = 512;
    /** turn stream heartbeat 间隔；用于 WebSocket/Event Resume 在长时间无业务事件时维持连接活跃。 */
    private Duration turnHeartbeatInterval = Duration.ofSeconds(15);
    /** run 级 Event Resume 的 live tail 故障后，按该间隔回查数据库直到 run 终态。 */
    private Duration resumePollInterval = Duration.ofSeconds(1);
    /**
     * run topic 实时事件来源。生产默认只消费 Redis，避免本机 local sink 与 Redis Pub/Sub
     * 两条异步源合并后出现同一 topic 的 seq 乱序。
     */
    private LiveSourceMode liveSourceMode = LiveSourceMode.REDIS_ONLY;
    /** 是否对 live topic 已到达事件做短窗口 seq 排序；不合并事件，只调整短暂乱序。 */
    private boolean liveReorderEnabled = true;
    /** live topic 短窗口排序等待时间；窗口到期后按 seq 升序逐条原样输出。 */
    private Duration liveReorderWindow = Duration.ofMillis(20);
    /** live topic 短窗口排序最多缓存事件数；达到上限立即 flush，避免单订阅无界缓存。 */
    private int liveReorderMaxEvents = 128;
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

    public LiveSourceMode getLiveSourceMode() {
        return liveSourceMode;
    }

    public void setLiveSourceMode(LiveSourceMode liveSourceMode) {
        this.liveSourceMode = liveSourceMode;
    }

    public LiveSourceMode normalizedLiveSourceMode() {
        return liveSourceMode == null ? LiveSourceMode.REDIS_ONLY : liveSourceMode;
    }

    public boolean isMergeLiveSourceMode() {
        return normalizedLiveSourceMode() == LiveSourceMode.MERGE;
    }

    public boolean isLiveReorderEnabled() {
        return liveReorderEnabled;
    }

    public void setLiveReorderEnabled(boolean liveReorderEnabled) {
        this.liveReorderEnabled = liveReorderEnabled;
    }

    public Duration getLiveReorderWindow() {
        return liveReorderWindow;
    }

    public void setLiveReorderWindow(Duration liveReorderWindow) {
        this.liveReorderWindow = liveReorderWindow;
    }

    public Duration normalizedLiveReorderWindow() {
        if (liveReorderWindow == null || liveReorderWindow.isNegative()) {
            return Duration.ofMillis(20);
        }
        return liveReorderWindow;
    }

    public int getLiveReorderMaxEvents() {
        return liveReorderMaxEvents;
    }

    public void setLiveReorderMaxEvents(int liveReorderMaxEvents) {
        this.liveReorderMaxEvents = liveReorderMaxEvents;
    }

    public int normalizedLiveReorderMaxEvents() {
        return Math.max(1, liveReorderMaxEvents);
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

    public enum LiveSourceMode {
        /** 生产默认模式：WebSocket/Event Resume live tail 只消费 Redis live bus。 */
        REDIS_ONLY,
        /** 兼容模式：合并本机 local sink 与 Redis live bus。 */
        MERGE,
        /** 单机调试模式：只消费本机 local sink。 */
        LOCAL_ONLY
    }
}
