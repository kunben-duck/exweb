package com.huawei.finance.front.one.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.StoredChatEvent;
import com.huawei.finance.front.one.infrastructure.redis.FinanceExRedisKeyBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.test.StepVerifier;

class RedisChatLiveEventBusTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final FinanceExRedisKeyBuilder redisKeys = FinanceExRedisKeyBuilder.ofEnv("test");
    private final ApplicationInstanceIdProvider instanceIdProvider = () -> "instance-a";

    @Test
    void publishIncludesCurrentInstanceIdForSelfEchoFiltering() throws Exception {
        CapturingStringRedisTemplate redis = new CapturingStringRedisTemplate();
        RedisChatLiveEventBus bus = newBus(redis);
        ChatEvent event = event(1L);

        bus.publish("chat-run-run1", event);

        org.assertj.core.api.Assertions.assertThat(redis.channel).isEqualTo(redisKeys.chatStreamChannel("chat-run-run1"));
        JsonNode body = objectMapper.readTree(redis.message);
        org.assertj.core.api.Assertions.assertThat(body.path("publisherInstanceId").asText()).isEqualTo("instance-a");
        org.assertj.core.api.Assertions.assertThat(body.path("runId").asText()).isEqualTo("run1");
        org.assertj.core.api.Assertions.assertThat(body.path("sessionId").asText()).isEqualTo("session1");
        org.assertj.core.api.Assertions.assertThat(body.path("sequence").asLong()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(body.path("eventType").asText()).isEqualTo("message.delta");
    }

    @Test
    void subscribeDropsSelfEchoPayloadFromCurrentInstance() throws Exception {
        RedisChatLiveEventBus bus = newBus(new CapturingStringRedisTemplate());

        StepVerifier.create(bus.subscribe("chat-run-run1"))
                .then(() -> bus.onMessage(message(Map.of(
                        "publisherInstanceId", "instance-a",
                        "runId", "run1",
                        "sessionId", "session1",
                        "sequence", 1L,
                        "eventType", "message.delta",
                        "createdAt", Instant.now(),
                        "payload", Map.of("delta", "self")
                )), null))
                .expectNoEvent(Duration.ofMillis(100))
                .thenCancel()
                .verify();
    }

    @Test
    void selfEchoTerminalCompletesRedisSideWithoutDeliveringDuplicateEvent() {
        RedisChatLiveEventBus bus = newBus(new CapturingStringRedisTemplate());

        StepVerifier.create(bus.subscribe("chat-run-run1"))
                .then(() -> bus.onMessage(message(Map.of(
                        "publisherInstanceId", "instance-a",
                        "runId", "run1",
                        "sessionId", "session1",
                        "sequence", 9L,
                        "eventType", "run.completed",
                        "createdAt", Instant.now(),
                        "payload", Map.of("status", "COMPLETED")
                )), null))
                .verifyComplete();
    }

    @Test
    void subscribeAcceptsRemoteAndLegacyPayloads() throws Exception {
        RedisChatLiveEventBus bus = newBus(new CapturingStringRedisTemplate());

        StepVerifier.create(bus.subscribe("chat-run-run1").take(2))
                .then(() -> {
                    bus.onMessage(message(Map.of(
                            "publisherInstanceId", "instance-b",
                            "runId", "run1",
                            "sessionId", "session1",
                            "sequence", 2L,
                            "eventType", "message.delta",
                            "createdAt", Instant.now(),
                            "payload", Map.of("delta", "remote")
                    )), null);
                    bus.onMessage(message(Map.of(
                            "runId", "run1",
                            "sessionId", "session1",
                            "sequence", 3L,
                            "eventType", "message.delta",
                            "createdAt", Instant.now(),
                            "payload", Map.of("delta", "legacy")
                    )), null);
                })
                .assertNext(event -> {
                    org.assertj.core.api.Assertions.assertThat(event.sequence()).isEqualTo(2L);
                    org.assertj.core.api.Assertions.assertThat(event.payload()).containsEntry("delta", "remote");
                })
                .assertNext(event -> {
                    org.assertj.core.api.Assertions.assertThat(event.sequence()).isEqualTo(3L);
                    org.assertj.core.api.Assertions.assertThat(event.payload()).containsEntry("delta", "legacy");
                })
                .verifyComplete();
    }

    @Test
    void concurrentRemoteMessagesForSameTopicAreSerialized() throws Exception {
        RedisChatLiveEventBus bus = newBus(new CapturingStringRedisTemplate());
        int count = 64;
        CompletableFuture<List<ChatEvent>> received = bus.subscribe("chat-run-run1")
                .take(count)
                .collectList()
                .toFuture();

        ExecutorService executor = Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long seq = i + 1L;
            futures.add(executor.submit(() -> {
                start.await();
                bus.onMessage(message(Map.of(
                        "publisherInstanceId", "instance-b",
                        "runId", "run1",
                        "sessionId", "session1",
                        "sequence", seq,
                        "eventType", "message.delta",
                        "createdAt", Instant.now(),
                        "payload", Map.of("delta", "remote-" + seq)
                )), null);
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(2, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        List<ChatEvent> events = received.get(5, TimeUnit.SECONDS);
        org.assertj.core.api.Assertions.assertThat(events).hasSize(count);
        org.assertj.core.api.Assertions.assertThat(events.stream().map(ChatEvent::sequence).distinct().count())
                .isEqualTo(count);
    }

    private RedisChatLiveEventBus newBus(StringRedisTemplate redis) {
        return new RedisChatLiveEventBus(redis, objectMapper, new UnsupportedRedisConnectionFactory(), redisKeys, instanceIdProvider);
    }

    private ChatEvent event(long seq) {
        return new StoredChatEvent("run1", "session1", seq, "message.delta",
                Instant.now(), Map.of("delta", "hello"));
    }

    private Message message(Map<String, Object> body) {
        byte[] bodyBytes = writeBody(body);
        byte[] channelBytes = redisKeys.chatStreamChannel("chat-run-run1").getBytes(StandardCharsets.UTF_8);
        return new Message() {
            @Override
            public byte[] getBody() {
                return bodyBytes;
            }

            @Override
            public byte[] getChannel() {
                return channelBytes;
            }
        };
    }

    private byte[] writeBody(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static class CapturingStringRedisTemplate extends StringRedisTemplate {
        private String channel;
        private String message;

        @Override
        public Long convertAndSend(String channel, Object message) {
            this.channel = channel;
            this.message = String.valueOf(message);
            return 1L;
        }
    }

    private static class UnsupportedRedisConnectionFactory implements RedisConnectionFactory {
        @Override
        public boolean getConvertPipelineAndTxResults() {
            return true;
        }

        @Override
        public RedisConnection getConnection() {
            throw new UnsupportedOperationException("test fake does not connect to Redis");
        }

        @Override
        public RedisClusterConnection getClusterConnection() {
            return null;
        }

        @Override
        public RedisSentinelConnection getSentinelConnection() {
            return null;
        }

        @Override
        public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
            return null;
        }
    }
}
