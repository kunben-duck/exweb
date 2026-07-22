package com.huawei.it.ex.one.infrastructure.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * FinanceEX Redis 连接配置。
 *
 * <p>Redis 在本服务中只承担热缓存、取消标记、恢复锁优化和 WebSocket 跨实例 fanout，
 * 不承担最终事实源。生产环境可以通过 {@code mode=cluster} 切换到 Redis Cluster；
 * 本地开发默认仍使用 standalone，避免单机调试成本。</p>
 */
@ConfigurationProperties(prefix = "financeex.redis")
public class FinanceExRedisProperties {
    /** Redis 部署模式：standalone 用于本地单机，cluster 用于生产 Redis Cluster。 */
    private Mode mode = Mode.STANDALONE;
    /** standalone 模式 Redis 主机。 */
    private String host = "";
    /** standalone 模式 Redis 端口。 */
    private int port = 6379;
    /** standalone 模式数据库编号；Redis Cluster 不支持选择 database。 */
    private int database = 0;
    /** Redis 访问密码；为空表示不启用密码认证。 */
    private String password = "";
    /** Redis 命令超时时间。 */
    private Duration timeout = Duration.ofMillis(500);
    /** Redis 建连超时时间。 */
    private Duration connectTimeout = Duration.ofMillis(500);
    /** Redis Cluster 专属配置。 */
    private Cluster cluster = new Cluster();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.STANDALONE : mode;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getDatabase() {
        return database;
    }

    public void setDatabase(int database) {
        this.database = database;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Cluster getCluster() {
        return cluster;
    }

    public void setCluster(Cluster cluster) {
        this.cluster = cluster == null ? new Cluster() : cluster;
    }

    /**
     * 返回清洗后的 Redis Cluster 节点列表。
     *
     * <p>环境变量通常会以逗号分隔传入，例如
     * {@code FINANCEEX_REDIS_CLUSTER_NODES=10.0.0.1:6379,10.0.0.2:6379}。
     * 这里同时兼容 Spring 绑定成单个字符串或多个字符串的情况。</p>
     *
     * @return 非空、已去除首尾空白的 {@code host:port} 节点列表。
     */
    public List<String> normalizedClusterNodes() {
        return cluster.getNodes().stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    public enum Mode {
        /** 本地或单实例 Redis。 */
        STANDALONE,
        /** Redis Cluster，支持多节点 slot 分片。 */
        CLUSTER
    }

    /**
     * Redis Cluster 连接参数。
     */
    public static class Cluster {
        /** Redis Cluster 节点列表，格式为 {@code host:port}。 */
        private List<String> nodes = List.of();
        /** MOVED/ASK 重定向最大次数。 */
        private int maxRedirects = 3;
        /** Lettuce 周期性拓扑刷新间隔。 */
        private Duration topologyRefreshPeriod = Duration.ofSeconds(30);
        /** 是否启用 Lettuce 自适应拓扑刷新。 */
        private boolean adaptiveRefreshEnabled = true;

        public List<String> getNodes() {
            return nodes;
        }

        public void setNodes(List<String> nodes) {
            this.nodes = nodes == null ? List.of() : nodes;
        }

        public int getMaxRedirects() {
            return maxRedirects;
        }

        public void setMaxRedirects(int maxRedirects) {
            this.maxRedirects = maxRedirects;
        }

        public Duration getTopologyRefreshPeriod() {
            return topologyRefreshPeriod;
        }

        public void setTopologyRefreshPeriod(Duration topologyRefreshPeriod) {
            this.topologyRefreshPeriod = topologyRefreshPeriod;
        }

        public boolean isAdaptiveRefreshEnabled() {
            return adaptiveRefreshEnabled;
        }

        public void setAdaptiveRefreshEnabled(boolean adaptiveRefreshEnabled) {
            this.adaptiveRefreshEnabled = adaptiveRefreshEnabled;
        }
    }
}
