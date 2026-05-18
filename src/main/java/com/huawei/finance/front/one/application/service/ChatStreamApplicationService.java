package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatStreamTopics;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * 聊天事件流应用服务。
 *
 * <p>所有事件先写入 openGauss，再发布到 run 级实时 topic。WebSocket 用于当前页面新建 run 的
 * 实时订阅；run 级 SSE 用于新页签、新浏览器或跨电脑恢复已经存在的 active run，它会先从
 * openGauss 补发 afterSeq 之后的事实事件，再接续 live topic 直到 run 终态。</p>
 */
@Service
public class ChatStreamApplicationService {
    private final ChatEventStore eventStore;
    private final LocalChatEventStreamRegistry registry;
    private final ChatLiveEventBus liveEventBus;
    private final ChatRunRepository runRepository;
    private final ChatReadCursorApplicationService readCursorService;
    private final PermissionChecker permissionChecker;
    private final SessionRepository sessionRepository;

    public ChatStreamApplicationService(ChatEventStore eventStore, LocalChatEventStreamRegistry registry,
                                        ChatLiveEventBus liveEventBus, ChatRunRepository runRepository,
                                        ChatReadCursorApplicationService readCursorService,
                                        PermissionChecker permissionChecker,
                                        SessionRepository sessionRepository) {
        this.eventStore = eventStore;
        this.registry = registry;
        this.liveEventBus = liveEventBus;
        this.runRepository = runRepository;
        this.readCursorService = readCursorService;
        this.permissionChecker = permissionChecker;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 持久化并发布事件。
     *
     * @param event 原始事件。
     * @return 带持久化 seq 的事件。
     */
    public ChatEvent appendAndPublish(ChatEvent event) {
        ChatEvent persisted = eventStore.append(event);
        registry.publish(persisted);
        if (persisted.runId() != null && !persisted.runId().isBlank()) {
            liveEventBus.publish(ChatStreamTopics.runTopic(persisted.runId()), persisted);
        }
        return persisted;
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
                    return eventStore.findBySessionIdAndAfterSeq(sessionId, afterSeq);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    /**
     * 恢复并接续当前用户某个 run 的事件流。
     *
     * <p>该接口比会话级恢复更适合跨电脑续接“正在输出的当前回答”：新渲染实例应从
     * active run 的 firstSeq 之前开始补发。若 run 尚未终止，服务端会继续接入 live topic，
     * 直到 {@code run.completed/run.failed/run.cancelled} 终态事件到达后再关闭 SSE。read cursor
     * 只表示用户某个连接曾经确认消费到哪里，不能作为新页面已经渲染到哪里的证据。</p>
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
                        () -> liveBuffer(topicId, afterSeq),
                        liveBuffer -> Mono.fromCallable(() -> eventStore.findByRunIdAndAfterSeq(run.id(), afterSeq))
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
     * 记录当前 WebSocket 连接对某个 run topic 的 ack。
     *
     * @param user WebSocket 握手时解析出的用户身份快照。
     * @param topicId run 级 stream topic。
     * @param seq 客户端已经处理完成的最大事件序号。
     */
    public void acknowledgeRunTopic(UserContext user, String topicId, long seq) {
        ChatRun run = ensureRunTopicAccessible(user, topicId);
        readCursorService.acknowledgeTrustedSession(user, run.sessionId(), seq);
    }

    /**
     * 强制刷新当前连接上多个 topic 的 ack 游标。
     *
     * @param user WebSocket 握手时解析出的用户身份快照。
     * @param acknowledgements topicId 到最后 ack seq 的映射。
     */
    public void flushAcknowledgements(UserContext user, Map<String, Long> acknowledgements) {
        if (acknowledgements == null || acknowledgements.isEmpty()) {
            return;
        }
        acknowledgements.forEach((topicId, seq) -> {
            if (seq == null || seq <= 0) {
                return;
            }
            ChatRun run = ensureRunTopicAccessible(user, topicId);
            readCursorService.flushTrustedSession(user, run.sessionId(), seq);
        });
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
        return eventStore.findLatestSeqBySessionId(sessionId);
    }

    private void ensureOwnedSession(UserContext user, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        if (sessionRepository.findByTenantIdAndUserIdAndId(user.tenantId(), user.userId(), sessionId).isEmpty()) {
            throw new SecurityException("会话不存在或不属于当前用户");
        }
    }

    private RunTopicLiveBuffer liveBuffer(String topicId, long afterSeq) {
        Sinks.Many<ChatEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        Disposable subscription = deduplicate(Flux.merge(
                registry.subscribeRunTopic(topicId, afterSeq),
                liveEventBus.subscribe(topicId).filter(event -> event.sequence() > afterSeq)
        )).subscribe(
                event -> {
                    Sinks.EmitResult result = sink.tryEmitNext(event);
                    if (result.isFailure()) {
                        sink.tryEmitError(new IllegalStateException("run topic live buffer emit failed: " + result));
                    }
                },
                sink::tryEmitError,
                sink::tryEmitComplete
        );
        return new RunTopicLiveBuffer(sink.asFlux(), subscription);
    }

    private Flux<ChatEvent> deduplicate(Flux<ChatEvent> events) {
        return Flux.defer(() -> {
            Set<Long> seen = ConcurrentHashMap.newKeySet();
            return events.filter(event -> seen.add(event.sequence()));
        });
    }

    private Flux<ChatEvent> resumeRunWithLiveTail(ChatRun run, long afterSeq) {
        String topicId = ChatStreamTopics.runTopic(run.id());
        return Flux.using(
                () -> liveBuffer(topicId, afterSeq),
                liveBuffer -> Mono.fromCallable(() -> eventStore.findByRunIdAndAfterSeq(run.id(), afterSeq))
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
}
