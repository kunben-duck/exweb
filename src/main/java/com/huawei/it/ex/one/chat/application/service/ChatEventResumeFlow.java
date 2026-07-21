package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.chat.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.chat.application.model.ChatLiveRecoveryRequiredException;
import com.huawei.it.ex.one.chat.application.publisher.ChatLiveEventBus;
import com.huawei.it.ex.one.chat.application.repository.ChatEventStore;
import com.huawei.it.ex.one.chat.application.repository.ChatRunRepository;
import com.huawei.it.ex.one.chat.application.repository.SessionRepository;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatStreamTopics;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;

/** Package-local DB replay and live-tail workflow for persisted chat events. */
final class ChatEventResumeFlow {
    private final ChatEventStore eventStore;
    private final LocalChatEventStreamRegistry registry;
    private final ChatLiveEventBus liveEventBus;
    private final ChatRunRepository runRepository;
    private final PermissionChecker permissionChecker;
    private final SessionRepository sessionRepository;
    private final ChatWebSocketProperties webSocketProperties;
    private final ChatStreamProperties chatStreamProperties;
    private final AppLogger log;

    ChatEventResumeFlow(
            ChatEventStore eventStore,
            LocalChatEventStreamRegistry registry,
            ChatLiveEventBus liveEventBus,
            ChatRunRepository runRepository,
            PermissionChecker permissionChecker,
            SessionRepository sessionRepository,
            ChatWebSocketProperties webSocketProperties,
            ChatStreamProperties chatStreamProperties,
            AppLogger log) {
        this.eventStore = eventStore;
        this.registry = registry;
        this.liveEventBus = liveEventBus;
        this.runRepository = runRepository;
        this.permissionChecker = permissionChecker;
        this.sessionRepository = sessionRepository;
        this.webSocketProperties = webSocketProperties;
        this.chatStreamProperties = chatStreamProperties;
        this.log = log;
    }

    Flux<ChatEvent> resumeSession(UserContext user, String sessionId, long afterSeq) {
        return Mono.fromCallable(() -> {
                    permissionChecker.checkChatPermission(user);
                    ensureOwnedSession(user, sessionId);
                    return eventStore.findByOwnerAndSessionAfterSeq(
                            user.tenantId(), user.ownerUserId(), sessionId, afterSeq);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    Flux<ChatEvent> resumeRun(UserContext user, String runId, long afterSeq) {
        return Mono.fromCallable(() -> {
                    permissionChecker.checkChatPermission(user);
                    ChatRun run = runRepository.findByTenantIdAndUserIdAndId(
                                    user.tenantId(), user.ownerUserId(), runId)
                            .orElseThrow(() -> new SecurityException("run 不存在或不属于当前用户"));
                    ensureOwnedSession(user, run.sessionId());
                    return run;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(run -> resumeRunWithLiveTail(run, afterSeq));
    }

    Flux<ChatEvent> resumeRunTopic(UserContext user, String topicId, long afterSeq) {
        return Mono.fromCallable(() -> ensureRunTopicAccessible(user, topicId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(run -> Flux.using(
                        () -> liveBuffer(topicId, afterSeq, run.id(), run.sessionId()),
                        liveBuffer -> Mono.fromCallable(() -> eventStore.findByOwnerAndRunAfterSeq(
                                        user.tenantId(), user.ownerUserId(), run.sessionId(), run.id(), afterSeq))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMapMany(replay -> {
                                    long liveAfterSeq = replay.stream()
                                            .mapToLong(ChatEvent::sequence)
                                            .max()
                                            .orElse(afterSeq);
                                    return Flux.concat(
                                            Flux.fromIterable(replay),
                                            liveBuffer.events().filter(event -> event.sequence() > liveAfterSeq)
                                    );
                                }),
                        RunTopicLiveBuffer::dispose
                ));
    }

    ChatRun ensureRunTopicAccessible(UserContext user, String topicId) {
        permissionChecker.checkChatPermission(user);
        String runId = ChatStreamTopics.parseRunId(topicId)
                .orElseThrow(() -> new IllegalArgumentException("非法 stream topic: " + topicId));
        return runRepository.findByTenantIdAndUserIdAndId(user.tenantId(), user.ownerUserId(), runId)
                .orElseThrow(() -> new SecurityException("run 不存在或不属于当前用户"));
    }

    long latestSeq(UserContext user, String sessionId) {
        permissionChecker.checkChatPermission(user);
        ensureOwnedSession(user, sessionId);
        return eventStore.findLatestSeqByOwnerAndSession(user.tenantId(), user.ownerUserId(), sessionId);
    }

    private void ensureOwnedSession(UserContext user, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        if (sessionRepository.findByTenantIdAndUserIdAndId(
                user.tenantId(), user.ownerUserId(), sessionId).isEmpty()) {
            throw new SecurityException("会话不存在或不属于当前用户");
        }
    }

    private RunTopicLiveBuffer liveBuffer(
            String topicId,
            long afterSeq,
            String expectedRunId,
            String expectedSessionId) {
        Sinks.Many<ChatEvent> sink = Sinks.many().unicast().onBackpressureBuffer(
                Queues.<ChatEvent>get(webSocketProperties.normalizedLiveBufferCapacity()).get());
        Flux<ChatEvent> orderedLiveEvents = reorderBySeq(deduplicate(liveSource(topicId, afterSeq)
                .filter(event -> event != null
                        && expectedRunId.equals(event.runId())
                        && expectedSessionId.equals(event.sessionId()))));
        Disposable subscription = orderedLiveEvents.subscribe(
                event -> emitLiveEvent(sink, topicId, afterSeq, event),
                error -> sink.tryEmitError(toRecoveryRequired(topicId, afterSeq, error)),
                sink::tryEmitComplete
        );
        return new RunTopicLiveBuffer(sink.asFlux(), subscription);
    }

    private void emitLiveEvent(
            Sinks.Many<ChatEvent> sink,
            String topicId,
            long afterSeq,
            ChatEvent event) {
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            long recoveryAfterSeq = Math.max(afterSeq, Math.max(0L, event.sequence() - 1));
            sink.tryEmitError(new StreamRecoveryRequiredException(
                    topicId,
                    recoveryAfterSeq,
                    event.sequence(),
                    "LIVE_BUFFER_EMIT_FAILED",
                    "run topic live buffer emit failed: " + result));
        }
    }

    private Flux<ChatEvent> liveSource(String topicId, long afterSeq) {
        return switch (chatStreamProperties.normalizedLiveSourceMode()) {
            case REDIS_ONLY -> liveEventBus.subscribe(topicId).filter(event -> event.sequence() > afterSeq);
            case LOCAL_ONLY -> registry.subscribeRunTopic(topicId, afterSeq);
            case MERGE -> Flux.merge(
                    registry.subscribeRunTopic(topicId, afterSeq),
                    liveEventBus.subscribe(topicId).filter(event -> event.sequence() > afterSeq));
        };
    }

    private Flux<ChatEvent> reorderBySeq(Flux<ChatEvent> events) {
        int maxEvents = chatStreamProperties.normalizedLiveReorderMaxEvents();
        if (!chatStreamProperties.isLiveReorderEnabled()
                || maxEvents <= 1
                || chatStreamProperties.normalizedLiveReorderWindow().isZero()) {
            return events;
        }
        return events.bufferTimeout(maxEvents, chatStreamProperties.normalizedLiveReorderWindow())
                .concatMapIterable(buffer -> {
                    buffer.sort(Comparator.comparingLong(ChatEvent::sequence));
                    return buffer;
                });
    }

    private StreamRecoveryRequiredException toRecoveryRequired(
            String topicId,
            long afterSeq,
            Throwable error) {
        if (error instanceof ChatLiveRecoveryRequiredException recovery) {
            long recoveryAfterSeq = Math.max(afterSeq, recovery.recoveryAfterSeq());
            return new StreamRecoveryRequiredException(
                    topicId,
                    recoveryAfterSeq,
                    recovery.actualSeq(),
                    recovery.reason(),
                    "run topic live source requires recovery: " + recovery.getMessage());
        }
        return new StreamRecoveryRequiredException(
                topicId,
                afterSeq,
                afterSeq,
                "LIVE_SOURCE_ERROR",
                "run topic live source failed: " + error.getMessage());
    }

    private Flux<ChatEvent> deduplicate(Flux<ChatEvent> events) {
        return Flux.defer(() -> {
            Map<Long, Boolean> seen = new BoundedSeqMap(webSocketProperties.normalizedDeliveredSeqWindow());
            return events.filter(event -> {
                synchronized (seen) {
                    return seen.putIfAbsent(event.sequence(), Boolean.TRUE) == null;
                }
            });
        });
    }

    private Flux<ChatEvent> resumeRunWithLiveTail(ChatRun run, long afterSeq) {
        String topicId = ChatStreamTopics.runTopic(run.id());
        return Flux.using(
                () -> liveBuffer(topicId, afterSeq, run.id(), run.sessionId()),
                liveBuffer -> replayWithLiveTail(run, topicId, afterSeq, liveBuffer),
                RunTopicLiveBuffer::dispose);
    }

    private Flux<ChatEvent> replayWithLiveTail(
            ChatRun run,
            String topicId,
            long afterSeq,
            RunTopicLiveBuffer liveBuffer) {
        return Mono.fromCallable(() -> eventStore.findByOwnerAndRunAfterSeq(
                        run.tenantId(), run.userId(), run.sessionId(), run.id(), afterSeq))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(replay -> {
                    Flux<ChatEvent> replayFlux = Flux.fromIterable(replay);
                    if (run.status().terminal() || replay.stream().anyMatch(this::terminalEvent)) {
                        return replayFlux;
                    }
                    long liveAfterSeq = replay.stream()
                            .mapToLong(ChatEvent::sequence)
                            .max()
                            .orElse(afterSeq);
                    return Flux.concat(
                                    replayFlux,
                                    liveBuffer.events()
                                            .filter(event -> event.sequence() > liveAfterSeq)
                                            .onErrorResume(
                                                    StreamRecoveryRequiredException.class,
                                                    ex -> recoverEmpty(run, liveAfterSeq, ex)))
                            .takeUntil(this::terminalEvent);
                });
    }

    private Flux<ChatEvent> recoverEmpty(
            ChatRun run,
            long liveAfterSeq,
            StreamRecoveryRequiredException error) {
        log.warn(SystemErrorLogEntry.builder(
                        SystemErrorCode.WEBSOCKET_RECOVERY_FAILED,
                        "Run Event Resume live tail requires database recovery")
                .runId(run.id())
                .sessionId(run.sessionId())
                .operation("chat-event.resume.live-tail")
                .attribute("afterSeq", liveAfterSeq)
                .attribute("recoveryReason", error.reason())
                .build(), error);
        return Flux.empty();
    }

    private boolean terminalEvent(ChatEvent event) {
        return event != null && ("run.completed".equals(event.type())
                || "run.failed".equals(event.type())
                || "run.cancelled".equals(event.type())
                || "run.waiting_user".equals(event.type()));
    }

    private record RunTopicLiveBuffer(Flux<ChatEvent> events, Disposable subscription) {
        private void dispose() {
            if (subscription != null && !subscription.isDisposed()) {
                subscription.dispose();
            }
        }
    }

    private static final class BoundedSeqMap extends LinkedHashMap<Long, Boolean> {
        private final int maxSize;

        private BoundedSeqMap(int maxSize) {
            this.maxSize = maxSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > maxSize;
        }
    }
}
