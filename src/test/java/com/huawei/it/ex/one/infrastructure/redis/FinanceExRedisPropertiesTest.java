package com.huawei.it.ex.one.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FinanceExRedisPropertiesTest {
    @Test
    void defaultsDoNotPointToLocalRedis() {
        FinanceExRedisProperties properties = new FinanceExRedisProperties();

        assertThat(properties.getMode()).isEqualTo(FinanceExRedisProperties.Mode.STANDALONE);
        assertThat(properties.getHost()).isBlank();
        assertThat(properties.getPort()).isEqualTo(6379);
    }

    @Test
    void clusterNodesCanBeProvidedAsCommaSeparatedEnvironmentValue() {
        FinanceExRedisProperties properties = new FinanceExRedisProperties();
        properties.getCluster().setNodes(List.of(
                "10.0.0.1:6379, 10.0.0.2:6379",
                "",
                "10.0.0.1:6379",
                "10.0.0.3:6379"
        ));

        assertThat(properties.normalizedClusterNodes())
                .containsExactly("10.0.0.1:6379", "10.0.0.2:6379", "10.0.0.3:6379");
    }
}
