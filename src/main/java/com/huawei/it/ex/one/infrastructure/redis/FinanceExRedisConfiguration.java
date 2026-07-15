package com.huawei.it.ex.one.infrastructure.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * FinanceEX Redis 连接工厂配置。
 *
 * <p>Spring Boot 默认只根据 {@code spring.data.redis.*} 创建连接。为了让本服务在企业环境中明确支持
 * standalone 与 cluster 两种 Redis 拓扑，这里改为使用 {@code financeex.redis.*} 作为唯一配置入口。
 * 业务代码继续依赖 {@link StringRedisTemplate}，不感知底层 Redis 拓扑。</p>
 */
@Configuration
@EnableConfigurationProperties(FinanceExRedisProperties.class)
public class FinanceExRedisConfiguration {

    /**
     * 创建 Redis 连接工厂。
     *
     * @param properties FinanceEX Redis 配置。
     * @return Lettuce Redis 连接工厂。
     */
    @Bean
    @ConditionalOnMissingBean(RedisConnectionFactory.class)
    public RedisConnectionFactory redisConnectionFactory(FinanceExRedisProperties properties) {
        if (properties.getMode() == FinanceExRedisProperties.Mode.CLUSTER) {
            return clusterConnectionFactory(properties);
        }
        return standaloneConnectionFactory(properties);
    }

    /**
     * 创建字符串 Redis 模板。
     *
     * @param connectionFactory Redis 连接工厂。
     * @return StringRedisTemplate。
     */
    @Bean
    @ConditionalOnMissingBean(StringRedisTemplate.class)
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    private LettuceConnectionFactory standaloneConnectionFactory(FinanceExRedisProperties properties) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                requireText(properties.getHost(), "financeex.redis.host 不能为空"), properties.getPort());
        standalone.setDatabase(Math.max(0, properties.getDatabase()));
        applyPassword(standalone, properties.getPassword());
        return new LettuceConnectionFactory(standalone, lettuceClientConfiguration(properties, null));
    }

    private LettuceConnectionFactory clusterConnectionFactory(FinanceExRedisProperties properties) {
        List<String> nodes = properties.normalizedClusterNodes();
        if (nodes.isEmpty()) {
            throw new IllegalStateException("Redis Cluster 模式必须配置 financeex.redis.cluster.nodes");
        }
        RedisClusterConfiguration cluster = new RedisClusterConfiguration(nodes);
        cluster.setMaxRedirects(properties.getCluster().getMaxRedirects());
        applyPassword(cluster, properties.getPassword());
        return new LettuceConnectionFactory(cluster, lettuceClientConfiguration(properties, clusterClientOptions(properties)));
    }

    private LettuceClientConfiguration lettuceClientConfiguration(FinanceExRedisProperties properties, ClientOptions clientOptions) {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder = LettuceClientConfiguration.builder()
                .commandTimeout(nonNullDuration(properties.getTimeout(), Duration.ofMillis(500)))
                .shutdownTimeout(Duration.ofMillis(100));
        if (clientOptions != null) {
            builder.clientOptions(clientOptions);
        } else {
            builder.clientOptions(ClientOptions.builder()
                    .autoReconnect(true)
                    .socketOptions(SocketOptions.builder()
                            .connectTimeout(nonNullDuration(properties.getConnectTimeout(), Duration.ofMillis(500)))
                            .build())
                    .build());
        }
        return builder.build();
    }

    private ClientOptions clusterClientOptions(FinanceExRedisProperties properties) {
        ClusterTopologyRefreshOptions.Builder topology = ClusterTopologyRefreshOptions.builder()
                .enablePeriodicRefresh(nonNullDuration(properties.getCluster().getTopologyRefreshPeriod(), Duration.ofSeconds(30)));
        if (properties.getCluster().isAdaptiveRefreshEnabled()) {
            topology.enableAllAdaptiveRefreshTriggers();
        }
        return ClusterClientOptions.builder()
                .autoReconnect(true)
                .topologyRefreshOptions(topology.build())
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(nonNullDuration(properties.getConnectTimeout(), Duration.ofMillis(500)))
                        .build())
                .build();
    }

    private void applyPassword(RedisStandaloneConfiguration configuration, String password) {
        if (password != null && !password.isBlank()) {
            configuration.setPassword(RedisPassword.of(password));
        }
    }

    private void applyPassword(RedisClusterConfiguration configuration, String password) {
        if (password != null && !password.isBlank()) {
            configuration.setPassword(RedisPassword.of(password));
        }
    }

    private Duration nonNullDuration(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }
}
