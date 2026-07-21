package com.huawei.it.ex.one.chat.infrastructure.live;

import com.huawei.it.ex.one.common.redis.config.ChatLiveEventBusProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.it.ex.one.common.instance.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.redis.FinanceExRedisKeyBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Maintains the existing per-topic Redis publish queue and retry behavior. */
final class RedisChatLiveEventPublisher {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FinanceExRedisKeyBuilder redisKeys;
    private final ApplicationInstanceIdProvider instanceIdProvider;
    private final ChatLiveEventBusProperties properties;
    private final Executor publishExecutor;
    private final AppLogger log;
    private final Map<String, TopicPublisher> topicPublishers = new ConcurrentHashMap<>();

    RedisChatLiveEventPublisher(StringRedisTemplate redis, ObjectMapper objectMapper,
                                FinanceExRedisKeyBuilder redisKeys,
                                ApplicationInstanceIdProvider instanceIdProvider,
                                ChatLiveEventBusProperties properties,
                                Executor publishExecutor, AppLogger log) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.redisKeys = redisKeys;
        this.instanceIdProvider = instanceIdProvider;
        this.properties = properties;
        this.publishExecutor = publishExecutor;
        this.log = log;
    }

    void publish(String topicId, ChatEvent event) {
        if (topicId == null || topicId.isBlank() || event == null) {
            return;
        }
        try {
            PendingPublish pending = PendingPublish.event(topicId, channel(topicId), event,
                    objectMapper.writeValueAsString(
                            RedisChatLiveEventPayload.from(event, instanceIdProvider.currentInstanceId())));
            TopicPublisher publisher = topicPublishers.computeIfAbsent(topicId,
                    ignored -> new TopicPublisher(topicId, channel(topicId)));
            if (!publisher.offer(pending, properties)) {
                RecoveryMarker marker = publisher.markDegraded(pending, "REDIS_PUBLISH_QUEUE_OVERFLOW");
                logPublishFailure("REDIS_PUBLISH_QUEUE_OVERFLOW", pending, null, marker, 0);
                scheduleDrain(publisher);
                return;
            }
            scheduleDrain(publisher);
        } catch (RuntimeException | JsonProcessingException ex) {
            SystemErrorCode code = ex instanceof JsonProcessingException
                    ? SystemErrorCode.REDIS_SERIALIZATION_FAILED
                    : SystemErrorCode.REDIS_PUBLISH_FAILED;
            log.warn(SystemErrorLogEntry.builder(code,
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

    void clear() {
        topicPublishers.clear();
    }

    private String channel(String topicId) {
        return redisKeys.chatStreamChannel(topicId);
    }

    private void scheduleDrain(TopicPublisher publisher) {
        if (!publisher.startDraining()) {
            return;
        }
        try {
            publishExecutor.execute(() -> drainPublisher(publisher));
        } catch (RuntimeException ex) {
            publisher.stopDraining();
            RecoveryMarker marker = publisher.markDegraded(publisher.peek(), "REDIS_PUBLISH_EXECUTOR_REJECTED");
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.TASK_REJECTED,
                            "Redis live event publish executor rejected a task")
                    .operation("chat-live-bus.publish.schedule")
                    .attribute("topicId", publisher.topicId())
                    .attribute("recoveryAfterSeq", marker.recoveryAfterSeq())
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

    // This loop is the ordered per-topic publish/recovery state machine. Keeping it intact preserves queue ordering,
    // terminal cleanup and the exact delayed-retry scheduling points.
    @SuppressWarnings("PMD.CognitiveComplexity")
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
                    publisher.markDegraded(pending, "REDIS_PUBLISH_FAILED");
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
        return publishWithRetry(pending, RecoveryMarker.from(pending, "REDIS_PUBLISH_FAILED"));
    }

    private boolean publishWithRetry(PendingPublish pending, RecoveryMarker failureMarker) {
        int maxRetries = properties.normalizedRedisPublishRetryAttempts();
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                redis.convertAndSend(pending.channel(), pending.body());
                if (attempt > 0) {
                    log.info("Redis ChatLiveEventBus 发布重试成功，topicId={}, seq={}, retryCount={}, thread={}",
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
                        "Redis live event publish failed; subscribers must recover from the database event log")
                .runId(pending == null ? null : pending.runId())
                .sessionId(pending == null ? null : pending.sessionId())
                .operation("chat-live-bus.publish")
                .attribute("failureReason", reason)
                .attribute("topicId", pending == null ? marker.topicId() : pending.topicId())
                .attribute("sequence", pending == null ? marker.actualSeq() : pending.sequence())
                .attribute("recoveryAfterSeq", marker.recoveryAfterSeq())
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

    private record PendingPublish(
            String topicId,
            String channel,
            String runId,
            String sessionId,
            long sequence,
            String eventType,
            String body,
            int bodyBytes
    ) {
        private static PendingPublish event(String topicId, String channel, ChatEvent event, String body) {
            return new PendingPublish(topicId, channel, event.runId(), event.sessionId(), event.sequence(),
                    event.type(), body, body.getBytes(StandardCharsets.UTF_8).length);
        }

        private static PendingPublish control(String topicId, String channel, String body) {
            return new PendingPublish(topicId, channel, null, null, 0L,
                    "RECOVER_REQUIRED", body, body.getBytes(StandardCharsets.UTF_8).length);
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

        private synchronized PendingPublish peek() {
            return queue.peek();
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
}
