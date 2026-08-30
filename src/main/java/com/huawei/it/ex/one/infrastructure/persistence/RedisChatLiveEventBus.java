/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.it.ex.one.application.integration.conversation.ChatLiveRecoveryRequiredException;
import com.huawei.it.ex.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Redis Pub/Sub 的跨实例聊天实时事件总线。
 *
 * <p>该实现传播持久化事件和明确标记的 live-only 业务事件。持久化事件发布失败时可以提示前端使用
 * {@code afterSeq} 补发；live-only 事件发布失败时只记录失败，不能生成错误的恢复承诺。Redis Cluster 下
 * 不使用全局 pattern 订阅，而是在本机出现 run topic 订阅者时动态订阅对应 channel。</p>
 */
@Component
public class RedisChatLiveEventBus implements ChatLiveEventBus, MessageListener {
    private static final AppLogger log = AppLoggerFactory.getLogger(RedisChatLiveEventBus.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FinanceExRedisKeyBuilder redisKeys;
    private final ApplicationInstanceIdProvider instanceIdProvider;
    private final ChatLiveEventBusProperties properties;
    private final Executor publishExecutor;
    private final RedisMessageListenerContainer listenerContainer;
    private final Map<String, TopicSink> topicSinks = new ConcurrentHashMap<>();
    private final Map<String, TopicPublisher> topicPublishers = new ConcurrentHashMap<>();
    private volatile ChatStreamProperties chatStreamProperties = new ChatStreamProperties();

    @Autowired
    public RedisChatLiveEventBus(StringRedisTemplate redis, ObjectMapper objectMapper,
                                 RedisConnectionFactory connectionFactory,
                                 FinanceExRedisKeyBuilder redisKeys,
                                 ApplicationInstanceIdProvider instanceIdProvider,
                                 ChatLiveEventBusProperties properties,
                                 @Qualifier("redisChatLivePublishExecutor") Executor publishExecutor) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.redisKeys = redisKeys;
        this.instanceIdProvider = instanceIdProvider;
        this.properties = properties == null ? new ChatLiveEventBusProperties() : properties;
        this.publishExecutor = publishExecutor == null ? Runnable::run : publishExecutor;
        this.listenerContainer = new RedisMessageListenerContainer();
        this.listenerContainer.setConnectionFactory(connectionFactory);
    }

    @Autowired
    void setChatStreamProperties(ChatStreamProperties chatStreamProperties) {
        if (chatStreamProperties != null) {
            this.chatStreamProperties = chatStreamProperties;
        }
    }

    RedisChatLiveEventBus(StringRedisTemplate redis, ObjectMapper objectMapper,
                          RedisConnectionFactory connectionFactory,
                          FinanceExRedisKeyBuilder redisKeys,
                          ApplicationInstanceIdProvider instanceIdProvider) {
        this(redis, objectMapper, connectionFactory, redisKeys, instanceIdProvider,
                new ChatLiveEventBusProperties(), Runnable::run);
    }

    /**
     * 启动 Redis listener 容器。
     *
     * <p>具体 channel 在 {@link #subscribe(String)} 时按 topic 动态注册。</p>
     */
    @PostConstruct
    public void start() {
        try {
            listenerContainer.afterPropertiesSet();
            listenerContainer.start();
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_UNAVAILABLE,
                            "Redis live event listener failed to start; cross-instance delivery is degraded")
                    .operation("chat-live-bus.start")
                    .build(), ex);
        }
    }

    /**
     * 关闭 Redis listener。
     */
    @PreDestroy
    public void stop() {
        try {
            listenerContainer.stop();
            listenerContainer.destroy();
        } catch (Exception ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_SUBSCRIBE_FAILED,
                            "Redis live event listener failed to stop cleanly")
                    .operation("chat-live-bus.stop")
                    .retryable(false)
                    .build(), ex);
        } finally {
            topicPublishers.clear();
        }
    }

    @Override
    public void publish(String topicId, ChatEvent event) {
        publish(topicId, event, true);
    }

    @Override
    public void publishLiveOnly(String topicId, ChatEvent event) {
        publish(topicId, event, false);
    }

    private void publish(String topicId, ChatEvent event, boolean recoverable) {
        if (topicId == null || topicId.isBlank() || event == null) {
            return;
        }
        try {
            PendingPublish pending = PendingPublish.event(topicId, channel(topicId), event,
                    objectMapper.writeValueAsString(ChatLiveEventPayload.from(
                            event, instanceIdProvider.currentInstanceId())), recoverable);
            TopicPublisher publisher = topicPublishers.computeIfAbsent(topicId,
                    ignored -> new TopicPublisher(topicId, channel(topicId)));
            if (!publisher.offer(pending, properties)) {
                if (pending.recoverable()) {
                    RecoveryMarker marker = publisher.markDegraded(pending, "REDIS_PUBLISH_QUEUE_OVERFLOW");
                    logPublishFailure("REDIS_PUBLISH_QUEUE_OVERFLOW", pending, null, marker, 0);
                } else {
                    logPublishFailure("REDIS_PUBLISH_QUEUE_OVERFLOW", pending, null, null, 0);
                }
                scheduleDrain(publisher);
                return;
            }
            scheduleDrain(publisher);
        } catch (JsonProcessingException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_SERIALIZATION_FAILED,
                            "Redis live event publish could not be queued")
                    .runId(event.runId())
                    .sessionId(event.sessionId())
                    .operation("chat-live-bus.publish.enqueue")
                    .attribute("topicId", topicId)
                    .attribute("sequence", event.sequence())
                    .attribute("thread", Thread.currentThread().getName())
                    .attribute("interrupted", Thread.currentThread().isInterrupted())
                    .build(), ex);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_PUBLISH_FAILED,
                            "Redis live event publish could not be queued")
                    .runId(event.runId())
                    .sessionId(event.sessionId())
                    .operation("chat-live-bus.publish.enqueue")
                    .attribute("topicId", topicId)
                    .attribute("sequence", event.sequence())
                    .attribute("thread", Thread.currentThread().getName())
                    .attribute("interrupted", Thread.currentThread().isInterrupted())
                    .build(), ex);
        }
    }

    @Override
    public Flux<ChatEvent> subscribe(String topicId) {
        if (topicId == null || topicId.isBlank()) {
            return Flux.empty();
        }
        TopicSink topic = topicSinks.computeIfAbsent(topicId, this::registerTopic);
        if (!topic.registered()) {
            topicSinks.remove(topicId, topic);
            return Flux.error(new ChatLiveRecoveryRequiredException(topicId, 0, 0,
                    "REDIS_SUBSCRIBE_FAILED", "Redis live topic subscribe failed: " + topic.registrationFailureReason()));
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

    @Override
    public void onMessage(Message message, byte[] pattern) {
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
            ChatLiveEventPayload payload = objectMapper.treeToValue(root, ChatLiveEventPayload.class);
            if (isPublishedByCurrentInstance(payload)) {
                log.debug("Redis ChatLiveEventBus dropped a self-published echo. topicId={}, seq={}, instanceId={}",
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

    private String channel(String topicId) {
        return redisKeys.chatStreamChannel(topicId);
    }

    private TopicSink registerTopic(String topicId) {
        ChannelTopic redisTopic = new ChannelTopic(channel(topicId));
        TopicSink topic = new TopicSink(redisTopic);
        try {
            listenerContainer.addMessageListener(this, redisTopic);
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
            listenerContainer.removeMessageListener(this, topic.redisTopic());
            topic.markUnregistered();
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_SUBSCRIBE_FAILED,
                            "Redis live topic unsubscription failed")
                    .operation("chat-live-bus.unsubscribe")
                    .attribute("topicId", topicId)
                    .build(), ex);
        }
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
        /*
         * Redis Pub/Sub 不是可靠队列，不能在本地堆积历史。只有无法确认业务事件是否已实时投递时，
         * 才结束该 live 流，上层会引导前端使用数据库事实源做 Event Resume。
         */
        Sinks.EmitResult errorResult = sink.emitError(new IllegalStateException(
                "redis live sink emit failed, seq=" + event.sequence() + ", result=" + result));
        if (errorResult.isFailure()) {
            logEmitFailure("REDIS_EMIT_ERROR_FAILED", topicId, event, errorResult);
        }
    }

    private void logEmitFailure(String reason, String topicId, ChatEvent event, Sinks.EmitResult result) {
        String threadName = Thread.currentThread().getName();
        if (result == Sinks.EmitResult.FAIL_TERMINATED || result == Sinks.EmitResult.FAIL_CANCELLED) {
            log.debug("Redis ChatLiveEventBus delivery reached a terminated topic. "
                            + "reason={}, topicId={}, seq={}, result={}, thread={}",
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

    private boolean isPublishedByCurrentInstance(ChatLiveEventPayload payload) {
        /*
         * 滚动发布期间旧版本 payload 可能没有 publisherInstanceId。缺失时按远端事件处理，
         * 避免升级过程丢跨实例实时消息。只有 merge 兼容模式会同时消费 local sink 和 Redis，
         * 因此只在该模式丢弃同实例 self-echo；redis-only 生产模式必须消费本实例发布到 Redis 的事件。
         */
        return shouldDropSelfPublished(payload.publisherInstanceId());
    }

    private boolean shouldDropSelfPublished(String publisherInstanceId) {
        return chatStreamProperties.isMergeLiveSourceMode()
                && publisherInstanceId != null
                && publisherInstanceId.equals(instanceIdProvider.currentInstanceId());
    }

    private void scheduleDrain(TopicPublisher publisher) {
        if (!publisher.startDraining()) {
            return;
        }
        try {
            publishExecutor.execute(() -> drainPublisher(publisher));
        } catch (RuntimeException ex) {
            publisher.stopDraining();
            PendingPublish recoverable = publisher.firstRecoverable();
            RecoveryMarker marker = recoverable == null
                    ? null
                    : publisher.markDegraded(recoverable, "REDIS_PUBLISH_EXECUTOR_REJECTED");
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.TASK_REJECTED,
                            "Redis live event publish executor rejected a task")
                    .operation("chat-live-bus.publish.schedule")
                    .attribute("topicId", publisher.topicId())
                    .attribute("recoveryAfterSeq", marker == null ? null : marker.recoveryAfterSeq())
                    .attribute("liveOnly", marker == null)
                    .attribute("thread", Thread.currentThread().getName())
                    .attribute("interrupted", Thread.currentThread().isInterrupted())
                    .build(), ex);
        }
    }

    private void scheduleRecoveryRetry(TopicPublisher publisher) {
        if (!publisher.markRecoveryRetryScheduled()) {
            return;
        }
        long delayMillis = Math.max(1L, properties.normalizedRedisPublishRecoveryRetryInterval().toMillis());
        try {
            CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS, publishExecutor)
                    .execute(() -> {
                        publisher.clearRecoveryRetryScheduled();
                        if (publisher.hasWork()) {
                            scheduleDrain(publisher);
                        }
                    });
        } catch (RuntimeException ex) {
            publisher.clearRecoveryRetryScheduled();
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.TASK_REJECTED,
                            "Redis live event delayed retry executor rejected a task")
                    .operation("chat-live-bus.publish.retry-schedule")
                    .attribute("topicId", publisher.topicId())
                    .attribute("thread", Thread.currentThread().getName())
                    .attribute("interrupted", Thread.currentThread().isInterrupted())
                    .build(), ex);
        }
    }

    private void drainPublisher(TopicPublisher publisher) {
        boolean delayedRetryScheduled = false;
        try {
            while (true) {
                RecoveryMarker marker = publisher.recoveryMarker();
                if (marker != null && !publishRecoveryControl(publisher, marker)) {
                    scheduleRecoveryRetry(publisher);
                    delayedRetryScheduled = true;
                    return;
                }
                if (marker != null) {
                    publisher.clearRecoveryMarker(marker);
                }
                PendingPublish pending = publisher.poll();
                if (pending == null) {
                    return;
                }
                if (!publishWithRetry(pending)) {
                    if (pending.recoverable()) {
                        publisher.markDegraded(pending, "REDIS_PUBLISH_FAILED");
                    }
                    continue;
                }
                if (pending.terminal()) {
                    publisher.markTerminalSeen();
                }
            }
        } finally {
            publisher.stopDraining();
            if (publisher.hasWork() && !delayedRetryScheduled) {
                scheduleDrain(publisher);
            } else if (publisher.canRemove()) {
                topicPublishers.remove(publisher.topicId(), publisher);
            }
        }
    }

    private boolean publishRecoveryControl(TopicPublisher publisher, RecoveryMarker marker) {
        PendingPublish control = PendingPublish.control(publisher.topicId(), publisher.channel(),
                recoveryControlPayload(marker));
        return publishWithRetry(control, marker);
    }

    private String recoveryControlPayload(RecoveryMarker marker) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("controlType", "RECOVER_REQUIRED");
        root.put("publisherInstanceId", instanceIdProvider.currentInstanceId());
        root.put("topicId", marker.topicId());
        root.put("recoveryAfterSeq", marker.recoveryAfterSeq());
        root.put("actualSeq", marker.actualSeq());
        root.put("reason", marker.reason());
        root.put("createdAt", Instant.now().toString());
        return root.toString();
    }

    private boolean publishWithRetry(PendingPublish pending) {
        return publishWithRetry(pending, pending.recoverable()
                ? RecoveryMarker.from(pending, "REDIS_PUBLISH_FAILED")
                : null);
    }

    private boolean publishWithRetry(PendingPublish pending, RecoveryMarker failureMarker) {
        int maxRetries = properties.normalizedRedisPublishRetryAttempts();
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                redis.convertAndSend(pending.channel(), pending.body());
                if (attempt > 0) {
                    log.info("Redis ChatLiveEventBus publish retry succeeded. "
                                    + "topicId={}, seq={}, retryCount={}, thread={}",
                            pending.topicId(), pending.sequence(), attempt, Thread.currentThread().getName());
                }
                return true;
            } catch (RuntimeException ex) {
                if (attempt >= maxRetries) {
                    logPublishFailure("REDIS_PUBLISH_FAILED", pending, ex, failureMarker, attempt);
                    return false;
                }
                if (!sleepBeforeRetry()) {
                    logPublishFailure("REDIS_PUBLISH_INTERRUPTED", pending, ex, failureMarker, attempt);
                    return false;
                }
            }
        }
        return false;
    }

    private boolean sleepBeforeRetry() {
        long millis = properties.normalizedRedisPublishRetryBackoff().toMillis();
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void logPublishFailure(String reason, PendingPublish pending, RuntimeException ex,
                                   RecoveryMarker marker, int retryCount) {
        SystemErrorLogEntry event = SystemErrorLogEntry.builder(SystemErrorCode.REDIS_PUBLISH_FAILED,
                        marker == null
                                ? "Live-only Redis event publish failed and cannot be recovered"
                                : "Redis live event publish failed; subscribers must recover from the database event log")
                .runId(pending == null ? null : pending.runId())
                .sessionId(pending == null ? null : pending.sessionId())
                .operation("chat-live-bus.publish")
                .attribute("failureReason", reason)
                .attribute("topicId", pending == null
                        ? marker == null ? null : marker.topicId()
                        : pending.topicId())
                .attribute("sequence", pending == null
                        ? marker == null ? null : marker.actualSeq()
                        : pending.sequence())
                .attribute("recoveryAfterSeq", marker == null ? null : marker.recoveryAfterSeq())
                .attribute("liveOnly", marker == null)
                .attribute("retryCount", retryCount)
                .attribute("thread", Thread.currentThread().getName())
                .attribute("interrupted", Thread.currentThread().isInterrupted())
                .build();
        if (ex == null) {
            log.warn(event);
        } else {
            log.warn(event, ex);
        }
    }

    /**
     * Redis Pub/Sub 中传输的最小事件快照。
     *
     * @param publisherInstanceId 发布该事件的应用实例 ID；旧版本可能为空。
     * @param runId 本轮执行追踪标识。
     * @param sessionId 前端聊天会话标识。
     * @param sequence 数据库全局事件序号。
     * @param eventType 事件类型。
     * @param createdAt 事件创建时间。
     * @param payload 事件载荷。
     */
    private record ChatLiveEventPayload(
            String publisherInstanceId,
            String runId,
            String sessionId,
            long sequence,
            String eventType,
            Instant createdAt,
            Map<String, Object> payload
    ) {
        private static ChatLiveEventPayload from(ChatEvent event, String publisherInstanceId) {
            return new ChatLiveEventPayload(publisherInstanceId, event.runId(), event.sessionId(),
                    event.sequence(), event.type(),
                    event.createdAt(), event.payload());
        }

        private ChatEvent toEvent() {
            return new StoredChatEvent(runId, sessionId, sequence, eventType,
                    createdAt == null ? Instant.EPOCH : createdAt, payload == null ? Map.of() : payload);
        }
    }

    private record PendingPublish(
            String topicId,
            String channel,
            String runId,
            String sessionId,
            long sequence,
            String eventType,
            String body,
            int bodyBytes,
            boolean recoverable
    ) {
        private static PendingPublish event(
                String topicId,
                String channel,
                ChatEvent event,
                String body,
                boolean recoverable) {
            return new PendingPublish(topicId, channel, event.runId(), event.sessionId(), event.sequence(),
                    event.type(), body, body.getBytes(StandardCharsets.UTF_8).length, recoverable);
        }

        private static PendingPublish control(String topicId, String channel, String body) {
            return new PendingPublish(topicId, channel, null, null, 0L,
                    "RECOVER_REQUIRED", body, body.getBytes(StandardCharsets.UTF_8).length, true);
        }

        private boolean terminal() {
            return "run.completed".equals(eventType)
                    || "run.failed".equals(eventType)
                    || "run.cancelled".equals(eventType)
                    || "run.waiting_user".equals(eventType);
        }
    }

    private record RecoveryMarker(String topicId, long recoveryAfterSeq, long actualSeq, String reason) {
        private static RecoveryMarker from(PendingPublish pending, String reason) {
            if (pending == null) {
                return new RecoveryMarker("_", 0L, 0L, reason);
            }
            long actualSeq = Math.max(0L, pending.sequence());
            return new RecoveryMarker(pending.topicId(), Math.max(0L, actualSeq - 1), actualSeq, reason);
        }
    }

    private static final class TopicPublisher {
        private final String topicId;
        private final String channel;
        private final Queue<PendingPublish> queue = new ArrayDeque<>();
        private int queuedBytes;
        private boolean draining;
        private boolean terminalSeen;
        private boolean recoveryRetryScheduled;
        private RecoveryMarker recoveryMarker;

        private TopicPublisher(String topicId, String channel) {
            this.topicId = topicId;
            this.channel = channel;
        }

        private synchronized boolean offer(PendingPublish pending, ChatLiveEventBusProperties properties) {
            if (pending.bodyBytes() > properties.normalizedRedisPublishTopicMaxBytes()) {
                return false;
            }
            if (queue.size() >= properties.normalizedRedisPublishTopicQueueSize()) {
                return false;
            }
            long nextBytes = (long) queuedBytes + pending.bodyBytes();
            if (nextBytes > properties.normalizedRedisPublishTopicMaxBytes()) {
                return false;
            }
            queue.add(pending);
            queuedBytes = (int) nextBytes;
            return true;
        }

        private synchronized PendingPublish poll() {
            PendingPublish pending = queue.poll();
            if (pending != null) {
                queuedBytes = Math.max(0, queuedBytes - pending.bodyBytes());
            }
            return pending;
        }

        private synchronized PendingPublish firstRecoverable() {
            return queue.stream().filter(PendingPublish::recoverable).findFirst().orElse(null);
        }

        private synchronized boolean startDraining() {
            if (draining) {
                return false;
            }
            draining = true;
            return true;
        }

        private synchronized void stopDraining() {
            draining = false;
        }

        private synchronized boolean markRecoveryRetryScheduled() {
            if (recoveryRetryScheduled) {
                return false;
            }
            recoveryRetryScheduled = true;
            return true;
        }

        private synchronized void clearRecoveryRetryScheduled() {
            recoveryRetryScheduled = false;
        }

        private synchronized boolean hasWork() {
            return recoveryMarker != null || !queue.isEmpty();
        }

        private synchronized boolean canRemove() {
            return terminalSeen && recoveryMarker == null && queue.isEmpty() && !draining && !recoveryRetryScheduled;
        }

        private synchronized RecoveryMarker markDegraded(PendingPublish pending, String reason) {
            RecoveryMarker marker = RecoveryMarker.from(pending, reason);
            if (pending == null) {
                marker = new RecoveryMarker(topicId, 0L, 0L, reason);
            } else if (pending.terminal()) {
                terminalSeen = true;
            }
            if (recoveryMarker == null || marker.recoveryAfterSeq() < recoveryMarker.recoveryAfterSeq()) {
                recoveryMarker = marker;
            }
            return recoveryMarker;
        }

        private synchronized RecoveryMarker recoveryMarker() {
            return recoveryMarker;
        }

        private synchronized void clearRecoveryMarker(RecoveryMarker marker) {
            if (recoveryMarker == marker || recoveryMarker != null && recoveryMarker.equals(marker)) {
                recoveryMarker = null;
            }
        }

        private synchronized void markTerminalSeen() {
            terminalSeen = true;
        }

        private String topicId() {
            return topicId;
        }

        private String channel() {
            return channel;
        }
    }

    private static class TopicSink {
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
