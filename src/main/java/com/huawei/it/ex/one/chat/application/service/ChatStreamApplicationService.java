package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.chat.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.chat.application.repository.ChatEventStore;
import com.huawei.it.ex.one.chat.application.publisher.ChatLiveEventBus;
import com.huawei.it.ex.one.chat.application.repository.ChatRunRepository;
import com.huawei.it.ex.one.chat.application.repository.SessionRepository;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatStreamTopics;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import java.util.List;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 聊天事件流应用服务。
 *
 * <p>所有事件先写入数据库，再发布到 run 级实时 topic。WebSocket 用于当前页面新建 run 的
 * 实时订阅；run 级事件恢复用于新页签、新浏览器或跨电脑恢复已经存在的 active run，它会先从
 * 数据库补发 afterSeq 之后的事实事件，再接续 live topic。若 live source 异常，服务端返回恢复错误，
 * 不做循环 DB polling，避免 Redis 抖动时放大数据库压力。</p>
 */
@Service
public class ChatStreamApplicationService implements ChatEventStreamService {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatStreamApplicationService.class);

    private final ChatEventStore eventStore;
    private final LocalChatEventStreamRegistry registry;
    private final ChatLiveEventBus liveEventBus;
    private final PermissionChecker permissionChecker;
    private final ChatEventResumeFlow resumeFlow;

    public ChatStreamApplicationService(ChatEventStore eventStore, LocalChatEventStreamRegistry registry,
                                        ChatLiveEventBus liveEventBus, ChatRunRepository runRepository,
                                        PermissionChecker permissionChecker,
                                        SessionRepository sessionRepository,
                                        ChatWebSocketProperties webSocketProperties) {
        this(eventStore, registry, liveEventBus, runRepository, permissionChecker, sessionRepository,
                webSocketProperties, new ChatStreamProperties());
    }

    @Autowired
    public ChatStreamApplicationService(ChatEventStore eventStore, LocalChatEventStreamRegistry registry,
                                        ChatLiveEventBus liveEventBus, ChatRunRepository runRepository,
                                        PermissionChecker permissionChecker,
                                        SessionRepository sessionRepository,
                                        ChatWebSocketProperties webSocketProperties,
                                        ChatStreamProperties chatStreamProperties) {
        this.eventStore = eventStore;
        this.registry = registry;
        this.liveEventBus = liveEventBus;
        this.permissionChecker = permissionChecker;
        this.resumeFlow = new ChatEventResumeFlow(
                eventStore,
                registry,
                liveEventBus,
                runRepository,
                permissionChecker,
                sessionRepository,
                webSocketProperties,
                chatStreamProperties,
                log);
    }

    /**
     * 持久化并发布事件。
     *
     * @param event 原始事件。
     * @return 带持久化 seq 的事件。
     */
    public ChatEvent appendAndPublish(ChatEvent event) {
        ChatEvent persisted = appendWithoutPublish(event);
        publishPersisted(persisted);
        return persisted;
    }

    /**
     * 持久化事件但不发布实时消息。
     *
     * <p>stop 和 watchdog 终态需要先在同一数据库事务中提交 event、run、execution 和
     * Interaction claim。调用方必须在事务成功返回后再调用 {@link #publishPersisted(ChatEvent)}，
     * 避免 Redis/WebSocket 观察到尚未提交或随后回滚的终态。</p>
     *
     * @param event 原始事件。
     * @return 带持久化 seq 的事件。
     */
    public ChatEvent appendWithoutPublish(ChatEvent event) {
        return eventStore.append(event);
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
     * 在同一个 execution 写入权下批量持久化同一 run 的普通 Runtime 事件，暂不发布。
     *
     * @param events 同一 run 的有序事件。
     * @param claim 当前 execution 写入权声明。
     * @return 按输入顺序返回带持久化 seq 的事件。
     */
    public List<ChatEvent> appendBatchWithExecutionGuard(List<ChatEvent> events, RunExecutionClaim claim) {
        return eventStore.appendBatchWithExecutionGuard(events, claim);
    }

    /**
     * 查询某个 run 已经成功落库的事实事件。
     *
     * <p>该方法服务于 stop 后的部分回答固化：只使用数据库中已经获得 seq 的事件重建
     * assistant 历史消息，避免把还在下游传输中、但未写入事实源的 chunk 当作用户可见历史。
     * 删除会话会在事务提交后复用 stop 编排，此时会话已是 DELETED，因此这里以 run 的
     * tenant/user/session/run 归属边界查询事实事件，不再要求会话仍对前端可见。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param run 当前用户拥有的 run 快照。
     * @return run.started 之后已落库的事件，按 seq 正序排列。
     */
    public java.util.List<ChatEvent> findPersistedRunEvents(UserContext user, ChatRun run) {
        permissionChecker.checkChatPermission(user);
        long afterSeq = run.firstSeq() == null || run.firstSeq() <= 0 ? 0 : run.firstSeq() - 1;
        return eventStore.findByOwnerAndRunAfterSeq(user.tenantId(), user.ownerUserId(), run.sessionId(), run.id(), afterSeq);
    }

    /**
     * 发布已经写入数据库的事实事件。
     *
     * <p>该方法只接受持久化后的事件。调用方必须先完成数据库 append，避免 Redis 或本机
     * live sink 推送出无法被 Event Resume 恢复的“悬空事件”。默认生产模式只消费 Redis；
     * local sink 仍发布是为了支持 local-only/merge 回退和单机调试。</p>
     *
     * @param persisted 已持久化并带有 seq 的事件。
     */
    public void publishPersisted(ChatEvent persisted) {
        RuntimeException publishFailure = null;
        try {
            registry.publish(persisted);
        } catch (RuntimeException ex) {
            publishFailure = new IllegalStateException("本机聊天事件发布失败: runId=" + persisted.runId()
                    + ", sequence=" + persisted.sequence(), ex);
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.WEBSOCKET_SEND_FAILED,
                            "Persisted chat event publication to the local live stream failed")
                    .runId(persisted.runId())
                    .sessionId(persisted.sessionId())
                    .operation("chat-event.publish.local")
                    .attribute("sequence", persisted.sequence())
                    .build(), ex);
        }
        if (persisted.runId() != null && !persisted.runId().isBlank()) {
            try {
                liveEventBus.publish(ChatStreamTopics.runTopic(persisted.runId()), persisted);
            } catch (RuntimeException ex) {
                log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_PUBLISH_FAILED,
                                "Persisted chat event publication to the cross-instance live bus failed")
                        .runId(persisted.runId())
                        .sessionId(persisted.sessionId())
                        .operation("chat-event.publish.live-bus")
                        .attribute("sequence", persisted.sequence())
                        .build(), ex);
                if (publishFailure == null) {
                    publishFailure = new IllegalStateException("跨实例聊天事件发布失败: runId=" + persisted.runId()
                            + ", sequence=" + persisted.sequence(), ex);
                } else {
                    publishFailure.addSuppressed(ex);
                }
            }
        }
        if (publishFailure != null) {
            throw publishFailure;
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
        return resumeFlow.resumeSession(user, sessionId, afterSeq);
    }

    /**
     * 恢复并接续当前用户某个 run 的事件流。
     *
     * <p>该接口比会话级恢复更适合跨电脑续接“正在输出的当前回答”：新渲染实例应从
     * active run 的 firstSeq 之前开始补发。若 run 尚未终止，服务端会继续接入 live topic，
     * 直到 {@code run.completed/run.failed/run.cancelled/run.waiting_user} 终态事件到达后再关闭事件恢复连接。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param runId 需要恢复的 run 标识。
     * @param afterSeq 客户端已消费的最后事件序号。
     * @return 指定 run 中大于 afterSeq 的历史事件，以及后续 live 事件直到终态。
     */
    public Flux<ChatEvent> resumeRun(UserContext user, String runId, long afterSeq) {
        return resumeFlow.resumeRun(user, runId, afterSeq);
    }

    /**
     * 恢复当前连接用户可访问的 run topic 事件流。
     *
     * <p>该方法是 WebSocket 的核心订阅入口：先用连接身份校验 run 归属，再按数据库 seq
     * 补发历史事件，最后按 {@code financeex.chat-stream.live-source-mode} 接入实时事件源。</p>
     *
     * @param user WebSocket 握手时解析出的用户身份快照。
     * @param topicId {@code /chat/runs} 返回的 run 级 stream topic。
     * @param afterSeq 客户端已消费的最后事件序号。
     * @return run topic 事件流。
     */
    public Flux<ChatEvent> resumeRunTopic(UserContext user, String topicId, long afterSeq) {
        return resumeFlow.resumeRunTopic(user, topicId, afterSeq);
    }

    /**
     * 校验当前用户是否可以订阅指定 run topic。
     *
     * @param user WebSocket 握手时解析出的用户身份快照。
     * @param topicId {@code /chat/runs} 返回的 run 级 stream topic。
     * @return topic 对应 run 快照。
     */
    public ChatRun ensureRunTopicAccessible(UserContext user, String topicId) {
        return resumeFlow.ensureRunTopicAccessible(user, topicId);
    }

    /**
     * 查询当前用户某会话的最新事件序号。
     */
    public long latestSeq(UserContext user, String sessionId) {
        return resumeFlow.latestSeq(user, sessionId);
    }
}
