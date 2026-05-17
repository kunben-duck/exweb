package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatStreamTopics;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 聊天事件流应用服务。
 *
 * <p>所有事件先写入 openGauss，再发布到 WebSocket run topic。SSE 恢复接口只按
 * afterSeq 补发事实源中的缺失事件；实时输出统一由 WebSocket 承载。</p>
 */
@Service
public class ChatStreamApplicationService {
    private final ChatEventStore eventStore;
    private final LocalChatEventStreamRegistry registry;
    private final ChatLiveEventBus liveEventBus;
    private final ChatRunRepository runRepository;
    private final PermissionChecker permissionChecker;
    private final SessionRepository sessionRepository;

    public ChatStreamApplicationService(ChatEventStore eventStore, LocalChatEventStreamRegistry registry,
                                        ChatLiveEventBus liveEventBus, ChatRunRepository runRepository,
                                        PermissionChecker permissionChecker,
                                        SessionRepository sessionRepository) {
        this.eventStore = eventStore;
        this.registry = registry;
        this.liveEventBus = liveEventBus;
        this.runRepository = runRepository;
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
        return Mono.fromCallable(() -> {
                    ChatRun run = ensureRunTopicAccessible(user, topicId);
                    Flux<ChatEvent> live = deduplicate(Flux.merge(
                            registry.subscribeRunTopic(topicId, afterSeq),
                            liveEventBus.subscribe(topicId).filter(event -> event.sequence() > afterSeq)
                    ));
                    List<ChatEvent> replay = eventStore.findByRunIdAndAfterSeq(run.id(), afterSeq);
                    long liveAfterSeq = replay.stream().mapToLong(ChatEvent::sequence).max().orElse(afterSeq);
                    return new RunTopicSnapshot(replay, liveAfterSeq, live);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(snapshot -> Flux.concat(
                        Flux.fromIterable(snapshot.replay()),
                        snapshot.live().filter(event -> event.sequence() > snapshot.liveAfterSeq())
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

    private record RunTopicSnapshot(List<ChatEvent> replay, long liveAfterSeq, Flux<ChatEvent> live) {}

    private Flux<ChatEvent> deduplicate(Flux<ChatEvent> events) {
        return Flux.defer(() -> {
            Set<Long> seen = ConcurrentHashMap.newKeySet();
            return events.filter(event -> seen.add(event.sequence()));
        });
    }
}
