package com.huawei.it.ex.one.infrastructure.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

class RedisRuntimeBindingCacheTest {

    @Test
    void getFallsBackForJsonAndRedisFailures() throws Exception {
        CacheFixture jsonFixture = fixture(mock(ObjectMapper.class));
        when(jsonFixture.values().get(anyString())).thenReturn("invalid");
        when(jsonFixture.objectMapper().readValue("invalid", RuntimeBinding.class)).thenThrow(jsonFailure());

        assertThat(jsonFixture.cache().get("tenant1", "user1", "session1", null)).isEmpty();

        CacheFixture redisFixture = fixture(new ObjectMapper().findAndRegisterModules());
        when(redisFixture.values().get(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));

        assertThat(redisFixture.cache().get("tenant1", "user1", "session1", null)).isEmpty();
    }

    @Test
    void putSuppressesJsonAndRedisFailures() throws Exception {
        RuntimeBinding binding = binding();
        CacheFixture jsonFixture = fixture(mock(ObjectMapper.class));
        when(jsonFixture.objectMapper().writeValueAsString(binding)).thenThrow(jsonFailure());

        assertThatCode(() -> jsonFixture.cache().put(binding)).doesNotThrowAnyException();

        CacheFixture redisFixture = fixture(new ObjectMapper().findAndRegisterModules());
        doThrow(new IllegalStateException("Redis unavailable")).when(redisFixture.values())
                .set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> redisFixture.cache().put(binding)).doesNotThrowAnyException();
    }

    private CacheFixture fixture(ObjectMapper objectMapper) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisRuntimeBindingCache cache = new RedisRuntimeBindingCache(redis, objectMapper,
                new RuntimeBindingProperties(), FinanceExRedisKeyBuilder.ofEnv("test"));
        return new CacheFixture(cache, objectMapper, values);
    }

    private RuntimeBinding binding() {
        Instant now = Instant.now();
        return new RuntimeBinding("binding1", "tenant1", "user1", "session1", "domain-agent",
                "runtime-session1", RuntimeBindingStatus.ACTIVE, "run1", null, now, now, Map.of());
    }

    private JsonProcessingException jsonFailure() {
        return new JsonProcessingException("JSON failure") {
        };
    }

    private record CacheFixture(RedisRuntimeBindingCache cache, ObjectMapper objectMapper,
                                ValueOperations<String, String> values) {
    }
}
