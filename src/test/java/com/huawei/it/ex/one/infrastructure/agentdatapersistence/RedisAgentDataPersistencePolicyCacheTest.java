package com.huawei.it.ex.one.infrastructure.agentdatapersistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

class RedisAgentDataPersistencePolicyCacheTest {
    private static final String KEY =
            "fin_ex:test:agent_data_persistence:domain-agent:skill-1";

    @Test
    void writesOnlyPolicyValueWithConfiguredTtlAndEnvironmentKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisAgentDataPersistencePolicyCache cache = cache(redis);

        cache.put("domain-agent", "skill-1", AgentDataPersistencePolicy.FULL,
                Duration.ofMinutes(10));

        verify(values).set(KEY, "FULL", Duration.ofMinutes(10));
    }

    @Test
    void removesUnknownCachedPolicyAndTreatsItAsMiss() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(KEY)).thenReturn("UNKNOWN");
        RedisAgentDataPersistencePolicyCache cache = cache(redis);

        assertThat(cache.get("domain-agent", "skill-1")).isEmpty();
        verify(redis).delete(KEY);
    }

    private RedisAgentDataPersistencePolicyCache cache(StringRedisTemplate redis) {
        return new RedisAgentDataPersistencePolicyCache(
                redis,
                FinanceExRedisKeyBuilder.ofEnv("test"),
                new AgentDataPersistenceProperties());
    }
}
