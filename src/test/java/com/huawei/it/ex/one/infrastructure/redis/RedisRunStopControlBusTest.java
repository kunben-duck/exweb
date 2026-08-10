package com.huawei.it.ex.one.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.conversation.RunStopControlBus;
import com.huawei.it.ex.one.application.integration.identity.ApplicationInstanceIdProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;

class RedisRunStopControlBusTest {
    @Test
    void publishesFlatSensitiveFreeRequestAndReportsMissingOwnerSubscriber() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ApplicationInstanceIdProvider instanceIds = mock(ApplicationInstanceIdProvider.class);
        when(instanceIds.currentInstanceId()).thenReturn("instance-a");
        when(redis.convertAndSend(anyString(), anyString())).thenReturn(0L);
        ObjectMapper objectMapper = new ObjectMapper();
        RedisRunStopControlBus bus = new RedisRunStopControlBus(
                redis,
                objectMapper,
                FinanceExRedisKeyBuilder.ofEnv("test"),
                instanceIds,
                mock(RedisConnectionFactory.class));
        RunStopControlBus.Request request = new RunStopControlBus.Request(
                "stop-1", "run-1", "instance-a", "instance-b", 7L, "USER_STOP");

        RunStopControlBus.Delivery delivery = bus.send(request);
        RunStopControlBus.Response response = delivery.responses()
                .next()
                .block(Duration.ofSeconds(1));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(
                org.mockito.ArgumentMatchers.eq(
                        "fin_ex:test:chat_run_stop_control:instance-b"),
                payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(Set.of(
                "type", "requestId", "runId", "requesterInstanceId", "ownerInstanceId",
                "fencingToken", "reason"));
        assertThat(json.path("type").asText()).isEqualTo("STOP_REQUEST");
        assertThat(json.path("fencingToken").asLong()).isEqualTo(7L);
        assertThat(payload.getValue()).doesNotContainIgnoringCase(
                "cookie", "trace", "content", "parts", "metadata");
        assertThat(delivery.subscriberCount()).isZero();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(RunStopControlBus.Status.UNAVAILABLE);
    }
}
