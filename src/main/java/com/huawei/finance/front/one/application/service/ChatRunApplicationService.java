package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunCache;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.domain.chat.ActiveRunExistsException;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunMode;
import com.huawei.finance.front.one.domain.chat.ChatRunCancelSignal;
import com.huawei.finance.front.one.domain.chat.ChatRunStatus;
import com.huawei.finance.front.one.domain.chat.ChatRunStopDecision;
import com.huawei.finance.front.one.domain.chat.ChatRunStopResult;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatStreamTopics;
import com.huawei.finance.front.one.domain.chat.ChatStreamStatus;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * ChatRun 生命周期应用服务。
 *
 * <p>该服务集中维护 run 事实源、active run 热缓存和 cancel flag。事件流写入前后的
 * 状态迁移都通过这里完成，避免 Controller 或 Runtime adapter 直接操作 run 状态。</p>
 */
@Service
public class ChatRunApplicationService {
    private static final Duration RUN_STATUS_FALLBACK_INTERVAL = Duration.ofMillis(100);

    private final ChatRunRepository repository;
    private final ChatRunCache cache;
    private final ChatEventStore eventStore;
    private final ChatReadCursorApplicationService readCursorService;
    private final PermissionChecker permissionChecker;
    private final SessionRepository sessionRepository;
    private final Map<String, EventAcceptanceSnapshot> eventAcceptanceCache = new ConcurrentHashMap<>();

    public ChatRunApplicationService(ChatRunRepository repository, ChatRunCache cache, ChatEventStore eventStore,
                                     ChatReadCursorApplicationService readCursorService,
                                     PermissionChecker permissionChecker,
                                     SessionRepository sessionRepository) {
        this.repository = repository;
        this.cache = cache;
        this.eventStore = eventStore;
        this.readCursorService = readCursorService;
        this.permissionChecker = permissionChecker;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 创建 RUNNING 状态的 run 快照。
     */
    public ChatRun createRunning(String runId, UserContext user, String sessionId, RouteTarget route,
                                 RuntimeBinding binding, Map<String, Object> metadata,
                                 ChatRunMode runMode, String parentMessageId, String userMessageId) {
        rejectIfActiveRunExists(user, sessionId);
        Instant now = Instant.now();
        ChatRun run = new ChatRun(
                runId,
                user.tenantId(),
                user.userId(),
                sessionId,
                ChatRunStatus.RUNNING,
                route == null || route.type() == null ? null : route.type().name(),
                route == null ? null : route.selectedAgentCode(),
                binding == null ? null : binding.provider(),
                binding == null ? null : binding.runtimeSessionId(),
                runMode,
                parentMessageId,
                userMessageId,
                null,
                null,
                null,
                null,
                now,
                null,
                metadata == null ? Map.of() : metadata,
                now,
                now
        );
        if (!cache.tryClaimActive(run)) {
            throw new ActiveRunExistsException(sessionId, findActive(user.tenantId(), user.userId(), sessionId)
                    .map(ChatRun::id)
                    .orElse("unknown"));
        }
        try {
            return save(run);
        } catch (RuntimeException ex) {
            cache.evictActive(user.tenantId(), user.userId(), sessionId);
            throw ex;
        }
    }

    /**
     * 创建普通 NEXT 模式的 run。
     */
    public ChatRun createRunning(String runId, UserContext user, String sessionId, RouteTarget route,
                                 RuntimeBinding binding, Map<String, Object> metadata) {
        return createRunning(runId, user, sessionId, route, binding, metadata, ChatRunMode.NEXT, null, null);
    }

    /**
     * 根据已持久化事件推进 run 状态和序号。
     */
    public ChatRun observeEvent(ChatEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            return null;
        }
        Optional<ChatRun> current = repository.findById(event.runId());
        if (current.isEmpty()) {
            return null;
        }
        ChatRun run = current.get();
        if (run.status().terminal() || run.status() == ChatRunStatus.CANCELLING && !"run.cancelled".equals(event.type())) {
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
        eventAcceptanceCache.remove(runId);
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
        return new ChatRunStopResult(
                latest.id(),
                latest.sessionId(),
                latest.status(),
                latest.lastSeq() == null ? 0L : latest.lastSeq(),
                latest.finishedAt() == null ? Instant.now() : latest.finishedAt()
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
     * 不能作为取消事实源。事件追加前必须优先检查 Redis cancel flag，并周期性回源 openGauss
     * run 状态，确保 stop 请求打到任意节点后，其他节点不会继续写入新的 delta 或 completed。</p>
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
        boolean forceRepositoryCheck = "run.completed".equals(event.type()) || "run.failed".equals(event.type());
        return shouldAcceptNonCancelledEvent(event.runId(), forceRepositoryCheck);
    }

    /**
     * 查询当前用户某会话的流式状态。
     */
    public ChatStreamStatus streamStatus(UserContext user, String sessionId) {
        permissionChecker.checkChatPermission(user);
        ensureOwnedSession(user, sessionId);
        long latestSeq = eventStore.findLatestSeqBySessionId(sessionId);
        long readCursorSeq = readCursorService.findLastConsumedSeq(user, sessionId);
        Optional<ChatRun> active = findActive(user.tenantId(), user.userId(), sessionId);
        return active
                .map(run -> new ChatStreamStatus(sessionId, latestSeq, readCursorSeq, run.id(), run.status(),
                        ChatStreamTopics.runTopic(run.id()), run.firstSeq(), run.lastSeq(), run.cancellable()))
                .orElseGet(() -> new ChatStreamStatus(sessionId, latestSeq, readCursorSeq,
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
            eventAcceptanceCache.remove(saved.id());
        } else {
            cache.putActive(saved);
            if (saved.status() == ChatRunStatus.CANCELLING) {
                eventAcceptanceCache.remove(saved.id());
            }
        }
        return saved;
    }

    private boolean shouldAcceptNonCancelledEvent(String runId, boolean forceRepositoryCheck) {
        ChatRunCancelSignal signal = cache.cancellationSignal(runId);
        if (signal == ChatRunCancelSignal.REQUESTED) {
            return false;
        }
        if (signal == ChatRunCancelSignal.UNKNOWN) {
            forceRepositoryCheck = true;
        }
        Instant now = Instant.now();
        EventAcceptanceSnapshot snapshot = eventAcceptanceCache.get(runId);
        if (!forceRepositoryCheck && snapshot != null && snapshot.validAt(now)) {
            return snapshot.accept();
        }
        Optional<ChatRun> persisted = repository.findById(runId);
        if (persisted.isEmpty()) {
            eventAcceptanceCache.remove(runId);
            return false;
        }
        boolean accept = persisted.get().status() == ChatRunStatus.RUNNING;
        // 每个事件仍先看 Redis cancel flag；DB 状态只做短 TTL 快照，避免高频 token 输出把 openGauss 打成逐 token 查询。
        eventAcceptanceCache.put(runId, new EventAcceptanceSnapshot(accept, now.plus(RUN_STATUS_FALLBACK_INTERVAL)));
        return accept;
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

    /**
     * run 状态短周期判断缓存。
     *
     * @param accept 上一次 openGauss 判断结果。
     * @param expiresAt 该判断结果的过期时间。
     */
    private record EventAcceptanceSnapshot(boolean accept, Instant expiresAt) {
        private boolean validAt(Instant now) {
            return expiresAt != null && expiresAt.isAfter(now);
        }
    }
}
