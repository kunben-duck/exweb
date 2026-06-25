package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunCache;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ActiveRunExistsException;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunCancelSignal;
import com.huawei.finance.front.one.domain.chat.ChatRunStatus;
import com.huawei.finance.front.one.domain.chat.ChatRunStopDecision;
import com.huawei.finance.front.one.domain.chat.ChatRunStopResult;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatStreamStatus;
import com.huawei.finance.front.one.domain.chat.ChatStreamTopics;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * ChatRun 生命周期应用服务。
 *
 * <p>该服务集中维护 run 事实源、active run 热缓存和 cancel flag。事件流写入前后的
 * 状态迁移都通过这里完成，避免 Controller 或 Runtime adapter 直接操作 run 状态。</p>
 */
@Service
public class ChatRunApplicationService {
    private final ChatRunRepository repository;
    private final ChatRunCache cache;
    private final ChatEventStore eventStore;
    private final PermissionChecker permissionChecker;
    private final SessionRepository sessionRepository;
    private final ChatRunLeaseApplicationService leaseService;
    private final ObjectProvider<ChatRunRecoveryOrchestrator> recoveryOrchestratorProvider;

    @Autowired
    public ChatRunApplicationService(ChatRunRepository repository, ChatRunCache cache, ChatEventStore eventStore,
                                     PermissionChecker permissionChecker,
                                     SessionRepository sessionRepository,
                                     ChatRunLeaseApplicationService leaseService,
                                     ObjectProvider<ChatRunRecoveryOrchestrator> recoveryOrchestratorProvider) {
        this.repository = repository;
        this.cache = cache;
        this.eventStore = eventStore;
        this.permissionChecker = permissionChecker;
        this.sessionRepository = sessionRepository;
        this.leaseService = leaseService;
        this.recoveryOrchestratorProvider = recoveryOrchestratorProvider;
    }

    ChatRunApplicationService(ChatRunRepository repository, ChatRunCache cache, ChatEventStore eventStore,
                              PermissionChecker permissionChecker,
                              SessionRepository sessionRepository) {
        this.repository = repository;
        this.cache = cache;
        this.eventStore = eventStore;
        this.permissionChecker = permissionChecker;
        this.sessionRepository = sessionRepository;
        this.leaseService = null;
        this.recoveryOrchestratorProvider = null;
    }

    /**
     * 创建 RUNNING 状态的 run 快照。
     */
    public ChatRun createRunning(CreateChatRunContext context) {
        UserContext user = context.user();
        rejectIfActiveRunExists(user, context.sessionId());
        Instant now = Instant.now();
        ChatRun run = new ChatRun(
                context.runId(),
                user.tenantId(),
                user.userId(),
                context.sessionId(),
                ChatRunStatus.RUNNING,
                context.route() == null || context.route().type() == null ? null : context.route().type().name(),
                context.route() == null ? null : context.route().selectedAgentCode(),
                context.binding() == null ? null : context.binding().provider(),
                context.binding() == null ? null : context.binding().runtimeSessionId(),
                context.runMode(),
                context.parentMessageId(),
                context.userMessageId(),
                null,
                null,
                null,
                null,
                now,
                null,
                context.safeMetadata(),
                now,
                now
        );
        if (!cache.tryClaimActive(run)) {
            throw new ActiveRunExistsException(context.sessionId(), findActive(user.tenantId(), user.userId(), context.sessionId())
                    .map(ChatRun::id)
                    .orElse("unknown"));
        }
        try {
            return save(run);
        } catch (RuntimeException ex) {
            cache.evictActive(user.tenantId(), user.userId(), context.sessionId());
            throw ex;
        }
    }

    /**
     * 根据已持久化事件推进 run 状态和序号。
     */
    public ChatRun observeEvent(ChatEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            return null;
        }
        if ("message.delta".equals(event.type()) || "message.snapshot".equals(event.type())
                || "message.completed".equals(event.type())) {
            /*
             * 非终态消息事件的可靠顺序事实已经在 fin_ex_chat_event_t。高并发输出时不再逐事件
             * 更新 fin_ex_chat_run_t.last_seq，避免 run 表成为热点；stream-status.latestSeq 会直接
             * 从事件表查询，run.started 和 run 终态事件仍会推进 run 表状态。
             */
            return null;
        }
        Optional<ChatRun> current = repository.findById(event.runId());
        if (current.isEmpty()) {
            return null;
        }
        ChatRun run = current.get();
        if (run.status().terminal() || run.status() == ChatRunStatus.CANCELLING
                && !"run.cancelled".equals(event.type()) && !"run.failed".equals(event.type())) {
            return run;
        }
        ChatRun next = switch (event.type()) {
            case "run.started" -> run.withFirstSeq(event.sequence());
            case "run.completed" -> run.completed(event.sequence());
            case "run.failed" -> run.failed(event.sequence());
            case "run.cancelled" -> run.cancelled(event.sequence());
            default -> run.withLastSeq(event.sequence());
        };
        Object runtimeSessionId = event.payload() == null ? null : event.payload().get("runtimeSessionId");
        if (runtimeSessionId != null && !String.valueOf(runtimeSessionId).isBlank()
                && !String.valueOf(runtimeSessionId).equals(next.runtimeSessionId())) {
            next = next.withRuntimeSessionId(String.valueOf(runtimeSessionId));
        }
        return save(next);
    }

    /**
     * run.completed 后回填最终 assistant 消息，建立 run 与可见历史消息的关联。
     */
    public ChatRun bindAssistantMessage(String runId, String assistantMessageId) {
        return repository.findById(runId)
                .map(run -> save(run.withAssistantMessageId(assistantMessageId)))
                .orElse(null);
    }

    /**
     * 接收 stop 请求并写入取消标记。
     */
    public ChatRunStopDecision requestStop(UserContext user, String runId, String reason) {
        ChatRun run = requireOwnedRun(user, runId);
        if (!run.cancellable()) {
            return new ChatRunStopDecision(run, false);
        }
        cache.markCancellationRequested(runId);
        ChatRun cancelling = save(run.cancelling(reason == null || reason.isBlank() ? "USER_STOP" : reason));
        if (cancelling.status() != ChatRunStatus.CANCELLING) {
            return new ChatRunStopDecision(cancelling, false);
        }
        return new ChatRunStopDecision(cancelling, true);
    }

    /**
     * 根据最新 run 快照构造 stop 响应。
     */
    public ChatRunStopResult toStopResult(ChatRun run) {
        ChatRun latest = repository.findById(run.id()).orElse(run);
        String assistantMessageId = latest.assistantMessageId();
        boolean messageReady = assistantMessageId != null && !assistantMessageId.isBlank();
        return new ChatRunStopResult(
                latest.id(),
                latest.sessionId(),
                latest.status(),
                latest.lastSeq() == null ? 0L : latest.lastSeq(),
                latest.finishedAt() == null ? Instant.now() : latest.finishedAt(),
                messageReady,
                messageReady ? assistantMessageId : null,
                messageReady ? assistantMessageId : null
        );
    }

    /**
     * 按当前身份查询 run 并强制校验归属。
     *
     * @param user 当前用户上下文。
     * @param runId run 标识。
     * @return 当前用户拥有的 run 快照。
     */
    public ChatRun requireOwnedRun(UserContext user, String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        return repository.findByTenantIdAndUserIdAndId(user.tenantId(), user.userId(), runId)
                .orElseThrow(() -> new SecurityException("run 不存在或不属于当前用户"));
    }

    /**
     * 校验当前会话没有仍在执行的 run。
     *
     * <p>该方法用于创建用户消息前的快速保护，避免 active run 已存在时仍然写入新的用户消息节点。
     * 真正的并发声明仍在 {@link #createRunning} 中通过 Redis set-if-absent 完成。</p>
     */
    public void rejectIfActiveRunExists(UserContext user, String sessionId) {
        findActive(user.tenantId(), user.userId(), sessionId)
                .ifPresent(active -> {
                    throw new ActiveRunExistsException(sessionId, active.id());
                });
    }

    /**
     * 判断事件是否仍允许写入 run 事件流。
     *
     * <p>这是集群部署下的关键保护：当前 JVM subscription registry 只能加速本机资源释放，
     * 不能作为取消事实源。非终态事件只做 Redis cancel flag 快速判断，最终写入正确性由
     * 数据库 guarded insert 校验 run 状态和 execution fencing；run 终态和 run.cancelled
     * 仍回源 DB 做幂等保护，避免重复闭合。</p>
     */
    public boolean shouldAcceptEvent(ChatEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            return false;
        }
        if ("run.cancelled".equals(event.type())) {
            return repository.findById(event.runId())
                    .map(run -> run.status() == ChatRunStatus.RUNNING || run.status() == ChatRunStatus.CANCELLING)
                    .orElse(false);
        }
        if (cache.cancellationSignal(event.runId()) == ChatRunCancelSignal.REQUESTED) {
            return false;
        }
        if ("run.completed".equals(event.type()) || "run.failed".equals(event.type())) {
            return repository.findById(event.runId())
                    .map(run -> run.status() == ChatRunStatus.RUNNING)
                    .orElse(false);
        }
        return true;
    }

    /**
     * 查询当前用户某会话的流式状态。
     */
    public ChatStreamStatus streamStatus(UserContext user, String sessionId) {
        permissionChecker.checkChatPermission(user);
        ensureOwnedSession(user, sessionId);
        long latestSeq = eventStore.findLatestSeqByOwnerAndSession(user.tenantId(), user.userId(), sessionId);
        Optional<ChatRun> active = findActive(user.tenantId(), user.userId(), sessionId);
        if (active.isPresent() && leaseService != null && leaseService.isLeaseExpired(active.get().id())) {
            ChatRunRecoveryOrchestrator orchestrator = recoveryOrchestratorProvider == null
                    ? null
                    : recoveryOrchestratorProvider.getIfAvailable();
            if (orchestrator != null) {
                orchestrator.recoverExpiredRun(active.get().id());
                latestSeq = eventStore.findLatestSeqByOwnerAndSession(user.tenantId(), user.userId(), sessionId);
                active = findActive(user.tenantId(), user.userId(), sessionId);
            }
        }
        long currentLatestSeq = latestSeq;
        return active
                .map(run -> new ChatStreamStatus(sessionId, currentLatestSeq, run.id(), run.status(),
                        ChatStreamTopics.runTopic(run.id()), run.firstSeq(), run.lastSeq(), run.cancellable()))
                .orElseGet(() -> new ChatStreamStatus(sessionId, currentLatestSeq,
                        null, null, null, null, null, false));
    }

    private Optional<ChatRun> findActive(String tenantId, String userId, String sessionId) {
        Optional<ChatRun> cached = cache.getActive(tenantId, userId, sessionId);
        if (cached.isPresent()) {
            Optional<ChatRun> persisted = repository.findById(cached.get().id());
            if (persisted.isPresent() && !persisted.get().status().terminal()) {
                cache.putActive(persisted.get());
                return persisted;
            }
            cache.evictActive(tenantId, userId, sessionId);
        }
        Optional<ChatRun> persisted = repository.findActiveBySession(tenantId, userId, sessionId);
        persisted.ifPresent(cache::putActive);
        return persisted;
    }

    private ChatRun save(ChatRun run) {
        ChatRun saved = repository.save(run);
        if (saved.status().terminal()) {
            cache.evictActive(saved.tenantId(), saved.userId(), saved.sessionId());
        } else {
            cache.putActive(saved);
        }
        return saved;
    }

    private void ensureOwnedSession(UserContext user, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        Optional<ChatSession> session = sessionRepository.findByTenantIdAndUserIdAndId(user.tenantId(), user.userId(), sessionId);
        if (session.isEmpty()) {
            throw new SecurityException("会话不存在或不属于当前用户");
        }
    }

}
