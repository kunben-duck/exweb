package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.application.config.ChatWebSocketProperties;
import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatStreamTopics;
import com.huawei.finance.front.one.domain.chat.RunExecutionClaim;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;

/**
 * 聊天事件流应用服务。
 *
 * <p>所有事件先写入 openGauss，再发布到 run 级实时 topic。WebSocket 用于当前页面新建 run 的
 * 实时订阅；run 级事件恢复用于新页签、新浏览器或跨电脑恢复已经存在的 active run，它会先从
 * openGauss 补发 afterSeq 之后的事实事件，再接续 live topic 直到 run 终态。</p>
 */
@Service
public class ChatStreamApplicationService {
    private final ChatEventStore eventStore;
    private final LocalChatEventStreamRegistry registry;
    private final ChatLiveEventBus liveEventBus;
    private final ChatRunRepository runRepository;
    private final PermissionChecker permissionChecker;
    private final SessionRepository sessionRepository;
    private final ChatWebSocketProperties webSocketProperties;

    public ChatStreamApplicationService(ChatEventStore eventStore, LocalChatEventStreamRegistry registry,
                                        ChatLiveEventBus liveEventBus, ChatRunRepository runRepository,
                                        PermissionChecker permissionChecker,
                                        SessionRepository sessionRepository,
                                        ChatWebSocketProperties webSocketProperties) {
        this.eventStore = eventStore;
        this.registry = registry;
        this.liveEventBus = liveEventBus;
        this.runRepository = runRepository;
        this.permissionChecker = permissionChecker;
        this.sessionRepository = sessionRepository;
        this.webSocketProperties = webSocketProperties;
    }

    /**
     * 持久化并发布事件。
     *
     * @param event 原始事件。
     * @return 带持久化 seq 的事件。
     */
    public ChatEvent appendAndPublish(ChatEvent event) {
        ChatEvent persisted = eventStore.append(event);
        publishPersisted(persisted);
        return persisted;
    }

    /**
     * 在 execution 写入权保护下持久化事件，但暂不发布。
     *
     * <p>run.completed 需要先保证事件能通过 DB 栅栏，再写完整 assistant 历史消息，最后发布终态。
     * 因此主编排会先调用该方法拿到持久化 seq，再决定是否发布。</p>
     *
     * @param event 原始事件。
     * @param claim 当前后台执行流持有的写入权声明。
     * @return 带持久化 seq 的事件。
     */
    public ChatEvent appendWithExecutionGuard(ChatEvent event, RunExecutionClaim claim) {
        return eventStore.appendWithExecutionGuard(event, claim);
    }

    /**
     * 查询某个 run 已经成功落库的事实事件。
     *
     * <p>该方法服务于用户主动 stop 后的部分回答固化：只使用 openGauss 中已经获得 seq 的事件重建
     * assistant 历史消息，避免把还在下游传输中、但未写入事实源的 chunk 当作用户可见历史。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param run 当前用户拥有的 run 快照。
     * @return run.started 之后已落库的事件，按 seq 正序排列。
     */
    public java.util.List<ChatEvent> findPersistedRunEvents(UserContext user, ChatRun run) {
        permissionChecker.checkChatPermission(user);
        ensureOwnedSession(user, run.sessionId());
        long afterSeq = run.firstSeq() == null || run.firstSeq() <= 0 ? 0 : run.firstSeq() - 1;
        return eventStore.findByOwnerAndRunAfterSeq(user.tenantId(), user.userId(), run.sessionId(), run.id(), afterSeq);
    }

    /**
     * 发布已经写入 openGauss 的事实事件。
     *
     * <p>该方法只接受持久化后的事件。调用方必须先完成 openGauss append，避免 Redis 或本机
     * live sink 推送出无法被 Event Resume 恢复的“悬空事件”。</p>
     *
     * @param persisted 已持久化并带有 seq 的事件。
     */
    public void publishPersisted(ChatEvent persisted) {
        registry.publish(persisted);
        if (persisted.runId() != null && !persisted.runId().isBlank()) {
            liveEventBus.publish(ChatStreamTopics.runTopic(persisted.runId()), persisted);
        }
    }

    /**
     * 恢复当前用户某个会话的缺失事件。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessionId 会话标识。
     * @param afterSeq 客户端已消费的最后事件序号。
     * @return 只包含事实源中大于 afterSeq 的历史事件。
     */
    public Flux<ChatEvent> resumeSession(UserContext user, String sessionId, long afterSeq) {
        return Mono.fromCallable(() -> {
                    permissionChecker.checkChatPermission(user);
                    ensureOwnedSession(user, sessionId);
                    return eventStore.findByOwnerAndSessionAfterSeq(user.tenantId(), user.userId(), sessionId, afterSeq);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    /**
     * 恢复并接续当前用户某个 run 的事件流。
     *
     * <p>该接口比会话级恢复更适合跨电脑续接“正在输出的当前回答”：新渲染实例应从
     * active run 的 firstSeq 之前开始补发。若 run 尚未终止，服务端会继续接入 live topic，
     * 直到 {@code run.completed/run.failed/run.cancelled} 终态事件到达后再关闭事件恢复连接。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param runId 需要恢复的 run 标识。
     * @param afterSeq 客户端已消费的最后事件序号。
     * @return 指定 run 中大于 afterSeq 的历史事件，以及后续 live 事件直到终态。
     */
    public Flux<ChatEvent> resumeRun(UserContext user, String runId, long afterSeq) {
        return Mono.fromCallable(() -> {
                    permissionChecker.checkChatPermission(user);
                    ChatRun run = runRepository.findByTenantIdAndUserIdAndId(user.tenantId(), user.userId(), runId)
                            .orElseThrow(() -> new SecurityException("run 不存在或不属于当前用户"));
                    ensureOwnedSession(user, run.sessionId());
                    return run;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(run -> resumeRunWithLiveTail(run, afterSeq));
    }

    /**
     * 恢复当前连接用户可访问的 run topic 事件流。
     *
     * <p>该方法是 WebSocket 的核心订阅入口：先用连接身份校验 run 归属，再按 openGauss seq
     * 补发历史事件，最后同时接入本机 live sink 与 Redis Pub/Sub 远端事件。</p>
     *
     * @param user WebSocket 握手时解析出的用户身份快照。
     * @param topicId {@code /chat/runs} 返回的 run 级 stream topic。
     * @param afterSeq 客户端已消费的最后事件序号。
     * @return run topic 事件流。
     */
    public Flux<ChatEvent> resumeRunTopic(UserContext user, String topicId, long afterSeq) {
        return Mono.fromCallable(() -> ensureRunTopicAccessible(user, topicId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(run -> Flux.using(
                        () -> liveBuffer(topicId, afterSeq, run.id(), run.sessionId()),
                        liveBuffer -> Mono.fromCallable(() -> eventStore.findByOwnerAndRunAfterSeq(
                                        user.tenantId(), user.userId(), run.sessionId(), run.id(), afterSeq))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMapMany(replay -> {
                                    long liveAfterSeq = replay.stream().mapToLong(ChatEvent::sequence).max().orElse(afterSeq);
                                    return Flux.concat(
                                            Flux.fromIterable(replay),
                                            liveBuffer.events().filter(event -> event.sequence() > liveAfterSeq)
                                    );
                                }),
                        RunTopicLiveBuffer::dispose
                ));
    }

    /**
     * 校验当前用户是否可以订阅指定 run topic。
     *
     * @param user WebSocket 握手时解析出的用户身份快照。
     * @param topicId {@code /chat/runs} 返回的 run 级 stream topic。
     * @return topic 对应 run 快照。
     */
    public ChatRun ensureRunTopicAccessible(UserContext user, String topicId) {
        permissionChecker.checkChatPermission(user);
        String runId = ChatStreamTopics.parseRunId(topicId)
                .orElseThrow(() -> new IllegalArgumentException("非法 stream topic: " + topicId));
        return runRepository.findByTenantIdAndUserIdAndId(user.tenantId(), user.userId(), runId)
                .orElseThrow(() -> new SecurityException("run 不存在或不属于当前用户"));
    }

    /**
     * 查询当前用户某会话的最新事件序号。
     */
    public long latestSeq(UserContext user, String sessionId) {
        permissionChecker.checkChatPermission(user);
        ensureOwnedSession(user, sessionId);
        return eventStore.findLatestSeqByOwnerAndSession(user.tenantId(), user.userId(), sessionId);
    }

    private void ensureOwnedSession(UserContext user, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        if (sessionRepository.findByTenantIdAndUserIdAndId(user.tenantId(), user.userId(), sessionId).isEmpty()) {
            throw new SecurityException("会话不存在或不属于当前用户");
        }
    }

    private RunTopicLiveBuffer liveBuffer(String topicId, long afterSeq, String expectedRunId, String expectedSessionId) {
        Sinks.Many<ChatEvent> sink = Sinks.many().unicast().onBackpressureBuffer(
                Queues.<ChatEvent>get(webSocketProperties.normalizedLiveBufferCapacity()).get()
        );
        Disposable subscription = deduplicate(Flux.merge(
                registry.subscribeRunTopic(topicId, afterSeq),
                liveEventBus.subscribe(topicId).filter(event -> event.sequence() > afterSeq)
        )
                /*
                 * run topic 是前端实时隔离的核心边界。这里再按 runId + sessionId 做防御性过滤：
                 * 即使 Redis Pub/Sub 或本机 live source 出现错误投递，也不会把其他会话/run 的事件推给当前订阅。
                 */
                .filter(event -> event != null
                        && expectedRunId.equals(event.runId())
                        && expectedSessionId.equals(event.sessionId()))).subscribe(
                event -> {
                    Sinks.EmitResult result = sink.tryEmitNext(event);
                    if (result.isFailure()) {
                        sink.tryEmitError(new StreamRecoveryRequiredException(topicId, afterSeq,
                                "run topic live buffer emit failed: " + result));
                    }
                },
                error -> sink.tryEmitError(new StreamRecoveryRequiredException(topicId, afterSeq,
                        "run topic live source failed: " + error.getMessage())),
                sink::tryEmitComplete
        );
        return new RunTopicLiveBuffer(sink.asFlux(), subscription);
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
                liveBuffer -> Mono.fromCallable(() -> eventStore.findByOwnerAndRunAfterSeq(
                                run.tenantId(), run.userId(), run.sessionId(), run.id(), afterSeq))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(replay -> {
                            Flux<ChatEvent> replayFlux = Flux.fromIterable(replay);
                            if (run.status().terminal() || replay.stream().anyMatch(this::terminalEvent)) {
                                return replayFlux;
                            }
                            // liveBuffer 已经在查库前建立，避免 DB catchup 与 live 订阅之间产生事件空窗。
                            // 这里用 DB 已补发的最大 seq 作为 live 起点，保证同一事件不会同时从 replay 和 live 输出。
                            long liveAfterSeq = replay.stream()
                                    .mapToLong(ChatEvent::sequence)
                                    .max()
                                    .orElse(afterSeq);
                            return Flux.concat(
                                            replayFlux,
                                            liveBuffer.events().filter(event -> event.sequence() > liveAfterSeq)
                                    )
                                    .takeUntil(this::terminalEvent);
                        }),
                RunTopicLiveBuffer::dispose
        );
    }

    private boolean terminalEvent(ChatEvent event) {
        return event != null && ("run.completed".equals(event.type())
                || "run.failed".equals(event.type())
                || "run.cancelled".equals(event.type()));
    }

    /**
     * run topic 的实时事件缓冲。
     *
     * @param events 已经在订阅开始时接入的 live 事件流。
     * @param subscription live 订阅句柄，WebSocket 断开或取消订阅时必须释放。
     */
    private record RunTopicLiveBuffer(Flux<ChatEvent> events, Disposable subscription) {
        private void dispose() {
            if (subscription != null && !subscription.isDisposed()) {
                subscription.dispose();
            }
        }
    }

    /**
     * 有限窗口 seq 记忆表。
     *
     * <p>Redis Pub/Sub 与本机 registry 可能同时回流同一个事件，因此订阅侧需要去重。
     * 该窗口只服务实时投递，不作为可靠游标；可靠恢复仍以 openGauss event 表为准。</p>
     */
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
