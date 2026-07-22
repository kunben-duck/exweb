package com.huawei.it.ex.one.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.PatternMatchUtils;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 前端 WebSocket 连接治理配置。
 *
 * <p>WebSocket 在本服务中只承载 run topic 的实时事件订阅。该配置约束来源域名、
 * 单用户连接数、单连接订阅数和慢客户端缓冲，避免 MVC/Servlet 模式下长连接无限增长。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.websocket")
public class ChatWebSocketProperties {
    /** 允许建立 WebSocket 的 Origin pattern；生产必须配置企业前端域名。 */
    private List<String> allowedOriginPatterns = new ArrayList<>();
    /** 单个用户在当前 JVM 上允许同时保持的最大 WebSocket 连接数。 */
    private int maxConnectionsPerUser = 8;
    /** 单个 WebSocket 连接允许同时订阅的最大 run topic 数。 */
    private int maxSubscriptionsPerConnection = 8;
    /** 单个 run topic 在当前 JVM 上允许的最大本机订阅数。 */
    private int maxSubscribersPerTopic = 128;
    /** WebSocket 控制消息最大字节数，防止异常大帧压垮解析线程。 */
    private int maxInboundMessageBytes = 16 * 1024;
    /** WebSocket 出站队列容量，WebFlux handler 使用该值做慢客户端背压。 */
    private int outboundQueueSize = 256;
    /** run topic live 缓冲容量，超出后要求客户端使用 Event Resume 恢复。 */
    private int liveBufferCapacity = 512;
    /** 每个连接每个 topic 记住的已投递 seq 窗口大小，用于有限去重。 */
    private int deliveredSeqWindow = 2048;
    /** Servlet WebSocket 单次发送最大耗时。 */
    private Duration sendTimeLimit = Duration.ofSeconds(10);
    /** Servlet WebSocket 出站缓冲最大字节数。 */
    private int sendBufferSizeBytes = 512 * 1024;
    /** Servlet WebSocket 阻塞发送任务核心线程数；仅 MVC/Servlet WebSocket 使用。 */
    private int servletSendExecutorCoreSize = 4;
    /** Servlet WebSocket 阻塞发送任务最大线程数；仅 MVC/Servlet WebSocket 使用。 */
    private int servletSendExecutorMaxSize = 16;
    /** Servlet WebSocket 单连接出站队列容量；小于等于 0 时复用 outboundQueueSize。 */
    private int servletSendQueueCapacity = 0;
    /** Servlet WebSocket 单连接出站队列最大累计字节数。 */
    private DataSize servletSendQueueMaxBytes = DataSize.ofMegabytes(2);
    /** 是否使用 JDK 21 virtual thread 执行 Servlet WebSocket 阻塞发送任务。 */
    private boolean servletSendUseVirtualThreads = false;
    /** 连接无控制消息或投递活动超过该时间后，由服务端主动关闭。 */
    private Duration idleTimeout = Duration.ofMinutes(10);
    /** 空闲连接清理任务执行间隔。 */
    private Duration idleCheckInterval = Duration.ofMinutes(1);

    public List<String> getAllowedOriginPatterns() {
        return allowedOriginPatterns;
    }

    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
        if (allowedOriginPatterns == null) {
            this.allowedOriginPatterns = List.of();
            return;
        }
        this.allowedOriginPatterns = allowedOriginPatterns.stream()
                .filter(pattern -> pattern != null && !pattern.isBlank())
                .map(String::trim)
                .toList();
    }

    public int getMaxConnectionsPerUser() {
        return maxConnectionsPerUser;
    }

    public void setMaxConnectionsPerUser(int maxConnectionsPerUser) {
        this.maxConnectionsPerUser = maxConnectionsPerUser;
    }

    public int getMaxSubscriptionsPerConnection() {
        return maxSubscriptionsPerConnection;
    }

    public void setMaxSubscriptionsPerConnection(int maxSubscriptionsPerConnection) {
        this.maxSubscriptionsPerConnection = maxSubscriptionsPerConnection;
    }

    public int getMaxSubscribersPerTopic() {
        return maxSubscribersPerTopic;
    }

    public void setMaxSubscribersPerTopic(int maxSubscribersPerTopic) {
        this.maxSubscribersPerTopic = maxSubscribersPerTopic;
    }

    public int getMaxInboundMessageBytes() {
        return maxInboundMessageBytes;
    }

    public void setMaxInboundMessageBytes(int maxInboundMessageBytes) {
        this.maxInboundMessageBytes = maxInboundMessageBytes;
    }

    public int getOutboundQueueSize() {
        return outboundQueueSize;
    }

    public void setOutboundQueueSize(int outboundQueueSize) {
        this.outboundQueueSize = outboundQueueSize;
    }

    public int getLiveBufferCapacity() {
        return liveBufferCapacity;
    }

    public void setLiveBufferCapacity(int liveBufferCapacity) {
        this.liveBufferCapacity = liveBufferCapacity;
    }

    public int getDeliveredSeqWindow() {
        return deliveredSeqWindow;
    }

    public void setDeliveredSeqWindow(int deliveredSeqWindow) {
        this.deliveredSeqWindow = deliveredSeqWindow;
    }

    public Duration getSendTimeLimit() {
        return sendTimeLimit;
    }

    public void setSendTimeLimit(Duration sendTimeLimit) {
        this.sendTimeLimit = sendTimeLimit;
    }

    public int getSendBufferSizeBytes() {
        return sendBufferSizeBytes;
    }

    public void setSendBufferSizeBytes(int sendBufferSizeBytes) {
        this.sendBufferSizeBytes = sendBufferSizeBytes;
    }

    public int getServletSendExecutorCoreSize() {
        return servletSendExecutorCoreSize;
    }

    public void setServletSendExecutorCoreSize(int servletSendExecutorCoreSize) {
        this.servletSendExecutorCoreSize = servletSendExecutorCoreSize;
    }

    public int getServletSendExecutorMaxSize() {
        return servletSendExecutorMaxSize;
    }

    public void setServletSendExecutorMaxSize(int servletSendExecutorMaxSize) {
        this.servletSendExecutorMaxSize = servletSendExecutorMaxSize;
    }

    public int getServletSendQueueCapacity() {
        return servletSendQueueCapacity;
    }

    public void setServletSendQueueCapacity(int servletSendQueueCapacity) {
        this.servletSendQueueCapacity = servletSendQueueCapacity;
    }

    public DataSize getServletSendQueueMaxBytes() {
        return servletSendQueueMaxBytes;
    }

    public void setServletSendQueueMaxBytes(DataSize servletSendQueueMaxBytes) {
        this.servletSendQueueMaxBytes = servletSendQueueMaxBytes;
    }

    public boolean isServletSendUseVirtualThreads() {
        return servletSendUseVirtualThreads;
    }

    public void setServletSendUseVirtualThreads(boolean servletSendUseVirtualThreads) {
        this.servletSendUseVirtualThreads = servletSendUseVirtualThreads;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getIdleCheckInterval() {
        return idleCheckInterval;
    }

    public void setIdleCheckInterval(Duration idleCheckInterval) {
        this.idleCheckInterval = idleCheckInterval;
    }

    /**
     * 判断当前 Origin 是否允许建立 WebSocket。
     *
     * @param origin 浏览器握手 Origin；为空时通常来自非浏览器客户端，默认允许继续由身份鉴权兜底。
     * @return true 表示允许握手。
     */
    public boolean originAllowed(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        if (allowedOriginPatterns == null || allowedOriginPatterns.isEmpty()) {
            return false;
        }
        return allowedOriginPatterns.stream().anyMatch(pattern -> PatternMatchUtils.simpleMatch(pattern, origin));
    }

    public String[] allowedOriginPatternArray() {
        return allowedOriginPatterns == null ? new String[0] : allowedOriginPatterns.toArray(String[]::new);
    }

    public int normalizedMaxConnectionsPerUser() {
        return Math.max(1, maxConnectionsPerUser);
    }

    public int normalizedMaxSubscriptionsPerConnection() {
        return Math.max(1, maxSubscriptionsPerConnection);
    }

    public int normalizedMaxSubscribersPerTopic() {
        return Math.max(1, maxSubscribersPerTopic);
    }

    public int normalizedMaxInboundMessageBytes() {
        return Math.max(1024, maxInboundMessageBytes);
    }

    public int normalizedOutboundQueueSize() {
        return Math.max(16, outboundQueueSize);
    }

    public int normalizedLiveBufferCapacity() {
        return Math.max(16, liveBufferCapacity);
    }

    public int normalizedDeliveredSeqWindow() {
        return Math.max(16, deliveredSeqWindow);
    }

    public int normalizedSendTimeLimitMillis() {
        Duration normalized = sendTimeLimit == null ? Duration.ofSeconds(10) : sendTimeLimit;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, normalized.toMillis()));
    }

    public int normalizedSendBufferSizeBytes() {
        return Math.max(64 * 1024, sendBufferSizeBytes);
    }

    public int normalizedServletSendExecutorCoreSize() {
        return Math.max(1, servletSendExecutorCoreSize);
    }

    public int normalizedServletSendExecutorMaxSize() {
        return Math.max(normalizedServletSendExecutorCoreSize(), servletSendExecutorMaxSize);
    }

    public int normalizedServletSendQueueCapacity() {
        return servletSendQueueCapacity > 0 ? servletSendQueueCapacity : normalizedOutboundQueueSize();
    }

    public long normalizedServletSendQueueMaxBytes() {
        DataSize normalized = servletSendQueueMaxBytes == null ? DataSize.ofMegabytes(2) : servletSendQueueMaxBytes;
        return Math.max(64 * 1024L, normalized.toBytes());
    }

    public Duration normalizedIdleTimeout() {
        return idleTimeout == null ? Duration.ZERO : idleTimeout;
    }

    /**
     * @return 规范化后的空闲连接扫描间隔毫秒数；配置为空或小于 1 秒时使用 60 秒，避免调度线程忙等。
     */
    public long normalizedIdleCheckIntervalMillis() {
        Duration normalized = idleCheckInterval == null ? Duration.ofMinutes(1) : idleCheckInterval;
        return Math.max(1000L, normalized.toMillis());
    }
}
