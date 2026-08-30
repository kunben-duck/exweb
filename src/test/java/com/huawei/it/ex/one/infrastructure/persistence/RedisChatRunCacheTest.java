/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

class RedisChatRunCacheTest {

    @Test
    void getActiveFallsBackForJsonAndRedisFailures() throws Exception {
        CacheFixture jsonFixture = fixture(mock(ObjectMapper.class));
        when(jsonFixture.values().get(anyString())).thenReturn("invalid");
        when(jsonFixture.objectMapper().readValue("invalid", ChatRun.class)).thenThrow(jsonFailure());

        assertThat(jsonFixture.cache().getActive("tenant1", "user1", "session1")).isEmpty();

        CacheFixture redisFixture = fixture(new ObjectMapper().findAndRegisterModules());
        when(redisFixture.values().get(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));

        assertThat(redisFixture.cache().getActive("tenant1", "user1", "session1")).isEmpty();
    }

    @Test
    void tryClaimActiveFallsBackForJsonAndRedisFailures() throws Exception {
        ChatRun run = runningRun();
        CacheFixture jsonFixture = fixture(mock(ObjectMapper.class));
        when(jsonFixture.objectMapper().writeValueAsString(run)).thenThrow(jsonFailure());

        assertThat(jsonFixture.cache().tryClaimActive(run)).isFalse();

        CacheFixture redisFixture = fixture(new ObjectMapper().findAndRegisterModules());
        when(redisFixture.values().setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("Redis unavailable"));

        assertThat(redisFixture.cache().tryClaimActive(run)).isFalse();
    }

    @Test
    void putActiveSuppressesJsonAndRedisFailures() throws Exception {
        ChatRun run = runningRun();
        CacheFixture jsonFixture = fixture(mock(ObjectMapper.class));
        when(jsonFixture.objectMapper().writeValueAsString(run)).thenThrow(jsonFailure());

        assertThatCode(() -> jsonFixture.cache().putActive(run)).doesNotThrowAnyException();

        CacheFixture redisFixture = fixture(new ObjectMapper().findAndRegisterModules());
        doThrow(new IllegalStateException("Redis unavailable")).when(redisFixture.values())
                .set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> redisFixture.cache().putActive(run)).doesNotThrowAnyException();
    }

    private CacheFixture fixture(ObjectMapper objectMapper) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisChatRunCache cache = new RedisChatRunCache(redis, objectMapper, new ChatRunCacheProperties(),
                FinanceExRedisKeyBuilder.ofEnv("test"));
        return new CacheFixture(cache, objectMapper, values);
    }

    private ChatRun runningRun() {
        Instant now = Instant.now();
        return new ChatRun("run1", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                "AGENT_RUNTIME", null, "relay", null, null, null, null,
                now, null, Map.of(), now, now);
    }

    private JsonProcessingException jsonFailure() {
        return new JsonProcessingException("JSON failure") {
        };
    }

    private record CacheFixture(RedisChatRunCache cache, ObjectMapper objectMapper,
                                ValueOperations<String, String> values) {
    }
}
