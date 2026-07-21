package com.huawei.it.ex.one.chat.infrastructure.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.chat.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.common.instance.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.chat.application.model.ChatLiveRecoveryRequiredException;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.redis.FinanceExRedisKeyBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/** Maintains the existing per-topic Redis subscription and local sink behavior. */
final class RedisChatLiveEventSubscriber {
    private final ObjectMapper objectMapper;
    private final FinanceExRedisKeyBuilder redisKeys;
    private final ApplicationInstanceIdProvider instanceIdProvider;
    private final RedisMessageListenerContainer listenerContainer;
    private final MessageListener messageListener;
    private final AppLogger log;
    private final Map<String, TopicSink> topicSinks = new ConcurrentHashMap<>();
    private volatile ChatStreamProperties chatStreamProperties = new ChatStreamProperties();

    RedisChatLiveEventSubscriber(ObjectMapper objectMapper, FinanceExRedisKeyBuilder redisKeys,
                                 ApplicationInstanceIdProvider instanceIdProvider,
                                 RedisMessageListenerContainer listenerContainer,
                                 MessageListener messageListener, AppLogger log) {
        this.objectMapper = objectMapper;
        this.redisKeys = redisKeys;
        this.instanceIdProvider = instanceIdProvider;
        this.listenerContainer = listenerContainer;
        this.messageListener = messageListener;
        this.log = log;
    }

    void setChatStreamProperties(ChatStreamProperties chatStreamProperties) {
        if (chatStreamProperties != null) {
            this.chatStreamProperties = chatStreamProperties;
        }
    }

    Flux<ChatEvent> subscribe(String topicId) {
        if (topicId == null || topicId.isBlank()) {
            return Flux.empty();
        }
        TopicSink topic = topicSinks.computeIfAbsent(topicId, this::registerTopic);
        if (!topic.registered()) {
            topicSinks.remove(topicId, topic);
            return Flux.error(new ChatLiveRecoveryRequiredException(topicId, 0, 0,
                    "REDIS_SUBSCRIBE_FAILED", "Redis live topic subscribe failed: "
                    + topic.registrationFailureReason()));
        }
        topic.subscribers().incrementAndGet();
        return topic.sink().asFlux()
                .doFinally(signalType -> {
                    if (topic.subscribers().decrementAndGet() <= 0) {
                        topicSinks.remove(topicId, topic);
                        unregisterTopic(topicId, topic);
                    }
                });
    }

    void onMessage(Message message) {
        String topicId = topicFromChannel(new String(message.getChannel(), StandardCharsets.UTF_8));
        TopicSink sink = topicSinks.get(topicId);
        if (sink == null) {
            return;
        }
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(body);
            if (isRecoveryControl(root)) {
                handleRecoveryControl(topicId, sink, root);
                return;
            }
            RedisChatLiveEventPayload payload = objectMapper.treeToValue(root, RedisChatLiveEventPayload.class);
            if (isPublishedByCurrentInstance(payload)) {
                log.debug("Redis ChatLiveEventBus 丢弃本实例回声，topicId={}, seq={}, instanceId={}",
                        topicId, payload.sequence(), payload.publisherInstanceId());
                if (terminal(payload.eventType())) {
                    completeTopic(topicId, sink);
                }
                return;
            }
            ChatEvent event = payload.toEvent();
            Sinks.EmitResult nextResult = sink.emitNext(event);
            if (nextResult.isFailure()) {
                handleEmitFailure(topicId, sink, event, nextResult);
                return;
            }
            if (terminal(event)) {
                completeTopic(topicId, sink);
            }
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_DESERIALIZATION_FAILED,
                            "Redis live event message parsing failed")
                    .operation("chat-live-bus.message.parse")
                    .attribute("topicId", topicId)
                    .build(), ex);
        }
    }

    private boolean isRecoveryControl(JsonNode root) {
        return root != null && "RECOVER_REQUIRED".equals(root.path("controlType").asText());
    }

    private void handleRecoveryControl(String topicId, TopicSink sink, JsonNode root) {
        String publisherInstanceId = root.path("publisherInstanceId").asText(null);
        if (shouldDropSelfPublished(publisherInstanceId)) {
            return;
        }
        long recoveryAfterSeq = Math.max(0L, root.path("recoveryAfterSeq").asLong(0L));
        long actualSeq = Math.max(0L, root.path("actualSeq").asLong(recoveryAfterSeq));
        String reason = root.path("reason").asText("REDIS_PUBLISH_DEGRADED");
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_PUBLISH_FAILED,
                        "Redis live event bus received a recovery-required control message")
                .operation("chat-live-bus.recovery-control")
                .attribute("topicId", topicId)
                .attribute("recoveryAfterSeq", recoveryAfterSeq)
                .attribute("actualSeq", actualSeq)
                .attribute("recoveryReason", reason)
                .attribute("thread", Thread.currentThread().getName())
                .build());
        sink.emitError(new ChatLiveRecoveryRequiredException(topicId, recoveryAfterSeq, actualSeq,
                reason, "Redis live topic requires Event Resume from afterSeq=" + recoveryAfterSeq));
    }

    private TopicSink registerTopic(String topicId) {
        ChannelTopic redisTopic = new ChannelTopic(channel(topicId));
        TopicSink topic = new TopicSink(redisTopic);
        try {
            listenerContainer.addMessageListener(messageListener, redisTopic);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_SUBSCRIBE_FAILED,
                            "Redis live topic subscription failed; Event Resume is required")
                    .operation("chat-live-bus.subscribe")
                    .attribute("topicId", topicId)
                    .build(), ex);
            topic.markRegistrationFailed(ex.getMessage());
        }
        return topic;
    }

    private void unregisterTopic(String topicId, TopicSink topic) {
        if (!topic.registered()) {
            return;
        }
        try {
            listenerContainer.removeMessageListener(messageListener, topic.redisTopic());
            topic.markUnregistered();
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_SUBSCRIBE_FAILED,
                            "Redis live topic unsubscription failed")
                    .operation("chat-live-bus.unsubscribe")
                    .attribute("topicId", topicId)
                    .build(), ex);
        }
    }

    private String channel(String topicId) {
        return redisKeys.chatStreamChannel(topicId);
    }

    private String topicFromChannel(String channel) {
        return redisKeys.topicFromChatStreamChannel(channel);
    }

    private boolean terminal(ChatEvent event) {
        return event != null && terminal(event.type());
    }

    private boolean terminal(String eventType) {
        return "run.completed".equals(eventType)
                || "run.failed".equals(eventType)
                || "run.cancelled".equals(eventType)
                || "run.waiting_user".equals(eventType);
    }

    private void completeTopic(String topicId, TopicSink sink) {
        Sinks.EmitResult completeResult = sink.emitComplete();
        if (completeResult.isFailure()) {
            logEmitFailure("REDIS_EMIT_COMPLETE_FAILED", topicId, null, completeResult);
        }
        unregisterTopic(topicId, sink);
        topicSinks.remove(topicId, sink);
    }

    private void handleEmitFailure(String topicId, TopicSink sink, ChatEvent event, Sinks.EmitResult result) {
        logEmitFailure("REDIS_EMIT_NEXT_FAILED", topicId, event, result);
        if (result == Sinks.EmitResult.FAIL_TERMINATED || result == Sinks.EmitResult.FAIL_CANCELLED) {
            topicSinks.remove(topicId, sink);
            unregisterTopic(topicId, sink);
            return;
        }
        Sinks.EmitResult errorResult = sink.emitError(new IllegalStateException(
                "redis live sink emit failed, seq=" + event.sequence() + ", result=" + result));
        if (errorResult.isFailure()) {
            logEmitFailure("REDIS_EMIT_ERROR_FAILED", topicId, event, errorResult);
        }
    }

    private void logEmitFailure(String reason, String topicId, ChatEvent event, Sinks.EmitResult result) {
        String threadName = Thread.currentThread().getName();
        if (result == Sinks.EmitResult.FAIL_TERMINATED || result == Sinks.EmitResult.FAIL_CANCELLED) {
            log.debug("Redis ChatLiveEventBus 投递已结束 topic，reason={}, topicId={}, seq={}, result={}, thread={}",
                    reason, topicId, event == null ? null : event.sequence(), result, threadName);
            return;
        }
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_SUBSCRIBE_FAILED,
                        "Redis live event delivery to the local subscriber failed")
                .runId(event == null ? null : event.runId())
                .sessionId(event == null ? null : event.sessionId())
                .operation("chat-live-bus.deliver")
                .attribute("failureReason", reason)
                .attribute("topicId", topicId)
                .attribute("sequence", event == null ? null : event.sequence())
                .attribute("emitResult", result)
                .attribute("thread", threadName)
                .build());
    }

    private boolean isPublishedByCurrentInstance(RedisChatLiveEventPayload payload) {
        return shouldDropSelfPublished(payload.publisherInstanceId());
    }

    private boolean shouldDropSelfPublished(String publisherInstanceId) {
        return chatStreamProperties.isMergeLiveSourceMode()
                && publisherInstanceId != null
                && publisherInstanceId.equals(instanceIdProvider.currentInstanceId());
    }

    private static final class TopicSink {
        private final Sinks.Many<ChatEvent> sink = Sinks.many().multicast()
                .onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);
        private final AtomicInteger subscribers = new AtomicInteger();
        private final ChannelTopic redisTopic;
        private volatile boolean registered = true;
        private volatile String registrationFailureReason = "";

        private TopicSink(ChannelTopic redisTopic) {
            this.redisTopic = redisTopic;
        }

        private Sinks.Many<ChatEvent> sink() {
            return sink;
        }

        private synchronized Sinks.EmitResult emitNext(ChatEvent event) {
            return sink.tryEmitNext(event);
        }

        private synchronized Sinks.EmitResult emitComplete() {
            return sink.tryEmitComplete();
        }

        private synchronized Sinks.EmitResult emitError(Throwable error) {
            return sink.tryEmitError(error);
        }

        private AtomicInteger subscribers() {
            return subscribers;
        }

        private ChannelTopic redisTopic() {
            return redisTopic;
        }

        private boolean registered() {
            return registered;
        }

        private void markRegistrationFailed(String reason) {
            this.registered = false;
            this.registrationFailureReason = reason == null ? "" : reason;
        }

        private void markUnregistered() {
            this.registered = false;
        }

        private String registrationFailureReason() {
            return registrationFailureReason;
        }
    }
}
