package com.huawei.it.ex.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class RedisChatLiveEventBusTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final FinanceExRedisKeyBuilder redisKeys = FinanceExRedisKeyBuilder.ofEnv("test");
    private final ApplicationInstanceIdProvider instanceIdProvider = () -> "instance-a";

    @Test
    void productionConstructorIsExplicitlyAutowiredForSpring() {
        Constructor<?>[] autowiredConstructors = Arrays.stream(RedisChatLiveEventBus.class.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .toArray(Constructor<?>[]::new);

        org.assertj.core.api.Assertions.assertThat(autowiredConstructors).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(autowiredConstructors[0].getParameterCount()).isEqualTo(7);
    }

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
    void publishOnlyEnqueuesAndDrainsOnPublishExecutor() throws Exception {
        CapturingStringRedisTemplate redis = new CapturingStringRedisTemplate();
        ManualExecutor executor = new ManualExecutor();
        RedisChatLiveEventBus bus = newBus(redis, new ChatLiveEventBusProperties(), executor);

        bus.publish("chat-run-run1", event(1L));

        org.assertj.core.api.Assertions.assertThat(redis.message).isNull();
        executor.drain();
        org.assertj.core.api.Assertions.assertThat(redis.channel).isEqualTo(redisKeys.chatStreamChannel("chat-run-run1"));
        JsonNode body = objectMapper.readTree(redis.message);
        org.assertj.core.api.Assertions.assertThat(body.path("sequence").asLong()).isEqualTo(1L);
    }

    @Test
    void publishSuppressesJsonSerializationFailure() throws Exception {
        ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
        when(failingObjectMapper.writeValueAsString(any())).thenThrow(jsonFailure());
        RedisChatLiveEventBus bus = newBus(new CapturingStringRedisTemplate(), failingObjectMapper, Runnable::run);

        assertThatCode(() -> bus.publish("chat-run-run1", event(1L))).doesNotThrowAnyException();
    }

    @Test
    void publishSuppressesRuntimeFailure() {
        Executor failingExecutor = command -> {
            throw new IllegalStateException("Executor unavailable");
        };
        RedisChatLiveEventBus bus = newBus(new CapturingStringRedisTemplate(), objectMapper, failingExecutor);

        assertThatCode(() -> bus.publish("chat-run-run1", event(1L))).doesNotThrowAnyException();
    }

    @Test
    void publishRetriesTransientRedisFailure() {
        FailsThenSucceedsStringRedisTemplate redis = new FailsThenSucceedsStringRedisTemplate(1);
        ChatLiveEventBusProperties properties = new ChatLiveEventBusProperties();
        properties.setRedisPublishRetryBackoff(Duration.ZERO);
        RedisChatLiveEventBus bus = newBus(redis, properties, Runnable::run);

        bus.publish("chat-run-run1", event(1L));

        org.assertj.core.api.Assertions.assertThat(redis.attempts()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(redis.message).contains("\"sequence\":1");
    }

    @Test
    void subscribeAcceptsSelfEchoPayloadInRedisOnlyMode() throws Exception {
        RedisChatLiveEventBus bus = newBus(new CapturingStringRedisTemplate());

        StepVerifier.create(bus.subscribe("chat-run-run1").take(1))
                .then(() -> bus.onMessage(message(Map.of(
                        "publisherInstanceId", "instance-a",
                        "runId", "run1",
                        "sessionId", "session1",
                        "sequence", 1L,
                        "eventType", "message.delta",
                        "createdAt", Instant.now(),
                        "payload", Map.of("delta", "self")
                )), null))
                .assertNext(event -> {
                    org.assertj.core.api.Assertions.assertThat(event.sequence()).isEqualTo(1L);
                    org.assertj.core.api.Assertions.assertThat(event.payload()).containsEntry("delta", "self");
                })
                .verifyComplete();
    }

    @Test
    void subscribeDropsSelfEchoPayloadInMergeMode() throws Exception {
        RedisChatLiveEventBus bus = newBus(new CapturingStringRedisTemplate(),
                new ChatLiveEventBusProperties(), Runnable::run, liveSourceMode(ChatStreamProperties.LiveSourceMode.MERGE));

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
    void selfEchoTerminalCompletesRedisOnlySubscription() {
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
                .assertNext(event -> org.assertj.core.api.Assertions.assertThat(event.type()).isEqualTo("run.completed"))
                .verifyComplete();
    }

    @Test
    void subscribeAcceptsRemoteAndPayloadsWithoutPublisherInstance() throws Exception {
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
                            "payload", Map.of("delta", "without-instance-id")
                    )), null);
                })
                .assertNext(event -> {
                    org.assertj.core.api.Assertions.assertThat(event.sequence()).isEqualTo(2L);
                    org.assertj.core.api.Assertions.assertThat(event.payload()).containsEntry("delta", "remote");
                })
                .assertNext(event -> {
                    org.assertj.core.api.Assertions.assertThat(event.sequence()).isEqualTo(3L);
                    org.assertj.core.api.Assertions.assertThat(event.payload()).containsEntry("delta", "without-instance-id");
                })
                .verifyComplete();
    }

    @Test
    void subscribeReceivesRecoveryControlAsRecoveryError() {
        RedisChatLiveEventBus bus = newBus(new CapturingStringRedisTemplate());

        StepVerifier.create(bus.subscribe("chat-run-run1"))
                .then(() -> bus.onMessage(message(Map.of(
                        "controlType", "RECOVER_REQUIRED",
                        "publisherInstanceId", "instance-b",
                        "topicId", "chat-run-run1",
                        "recoveryAfterSeq", 41L,
                        "actualSeq", 42L,
                        "reason", "REDIS_PUBLISH_FAILED"
                )), null))
                .expectErrorSatisfies(ex -> {
                    org.assertj.core.api.Assertions.assertThat(ex)
                            .isInstanceOf(com.huawei.it.ex.one.application.integration.conversation.ChatLiveRecoveryRequiredException.class);
                    var recovery = (com.huawei.it.ex.one.application.integration.conversation.ChatLiveRecoveryRequiredException) ex;
                    org.assertj.core.api.Assertions.assertThat(recovery.recoveryAfterSeq()).isEqualTo(41L);
                    org.assertj.core.api.Assertions.assertThat(recovery.actualSeq()).isEqualTo(42L);
                    org.assertj.core.api.Assertions.assertThat(recovery.reason()).isEqualTo("REDIS_PUBLISH_FAILED");
                })
                .verify();
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
        return newBus(redis, new ChatLiveEventBusProperties(), Runnable::run);
    }

    private RedisChatLiveEventBus newBus(StringRedisTemplate redis, ChatLiveEventBusProperties properties,
                                         Executor executor) {
        return newBus(redis, properties, executor, new ChatStreamProperties());
    }

    private RedisChatLiveEventBus newBus(StringRedisTemplate redis, ChatLiveEventBusProperties properties,
                                         Executor executor, ChatStreamProperties streamProperties) {
        return newBus(redis, objectMapper, properties, executor, streamProperties);
    }

    private RedisChatLiveEventBus newBus(StringRedisTemplate redis, ObjectMapper mapper, Executor executor) {
        return newBus(redis, mapper, new ChatLiveEventBusProperties(), executor, new ChatStreamProperties());
    }

    private RedisChatLiveEventBus newBus(StringRedisTemplate redis, ObjectMapper mapper,
                                         ChatLiveEventBusProperties properties, Executor executor,
                                         ChatStreamProperties streamProperties) {
        RedisChatLiveEventBus bus = new RedisChatLiveEventBus(redis, mapper, new UnsupportedRedisConnectionFactory(), redisKeys,
                instanceIdProvider, properties, executor);
        bus.setChatStreamProperties(streamProperties);
        return bus;
    }

    private ChatStreamProperties liveSourceMode(ChatStreamProperties.LiveSourceMode mode) {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setLiveSourceMode(mode);
        return properties;
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

    private JsonProcessingException jsonFailure() {
        return new JsonProcessingException("JSON failure") {
        };
    }

    private static class CapturingStringRedisTemplate extends StringRedisTemplate {
        protected String channel;
        protected String message;

        @Override
        public Long convertAndSend(String channel, Object message) {
            this.channel = channel;
            this.message = String.valueOf(message);
            return 1L;
        }
    }

    private static class FailsThenSucceedsStringRedisTemplate extends CapturingStringRedisTemplate {
        private final AtomicInteger failuresRemaining;
        private final AtomicInteger attempts = new AtomicInteger();

        private FailsThenSucceedsStringRedisTemplate(int failuresRemaining) {
            this.failuresRemaining = new AtomicInteger(failuresRemaining);
        }

        @Override
        public Long convertAndSend(String channel, Object message) {
            attempts.incrementAndGet();
            if (failuresRemaining.getAndDecrement() > 0) {
                throw new IllegalStateException("Redis command interrupted");
            }
            return super.convertAndSend(channel, message);
        }

        private int attempts() {
            return attempts.get();
        }
    }

    private static class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void drain() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
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
