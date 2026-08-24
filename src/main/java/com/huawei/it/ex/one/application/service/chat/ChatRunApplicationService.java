package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.AgentModeBindingContext;
import com.huawei.it.ex.one.application.integration.agent.MessageSkillContext;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventStore;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunCache;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ActiveRunExistsException;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionUnavailableException;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunCancelSignal;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStopDecision;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatStreamStatus;
import com.huawei.it.ex.one.domain.chat.ChatStreamTopics;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.RelayOutputModeMetadata;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ChatRun 生命周期应用服务。
 *
 * <p>该服务集中维护 run 事实源、active run 热缓存和 cancel flag。事件流写入前后的
 * 状态迁移都通过这里完成，避免 Controller 或 Runtime adapter 直接操作 run 状态。</p>
 */
@Service
public class ChatRunApplicationService {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunApplicationService.class);
    private static final String SESSION_STATUS_ACTIVE = "ACTIVE";
    private static final String SESSION_STATUS_DELETED = "DELETED";
    private static final String INTERACTION_ID_METADATA = "interactionId";
    private static final String INTERACTION_TYPE_METADATA = "interactionType";
    private static final String INTERACTION_ASSISTANT_MESSAGE_ID_METADATA = "interactionAssistantMessageId";

    private final ChatRunRepository repository;
    private final ChatRunCache cache;
    private final ChatEventStore eventStore;
    private final PermissionChecker permissionChecker;
    private final SessionRepository sessionRepository;
    private final ChatRunLeaseApplicationService leaseService;
    private final ObjectProvider<ChatRunRecoveryOrchestrator> recoveryOrchestratorProvider;
    private final ObjectProvider<ChatInteractionApplicationService> interactionServiceProvider;
    private final ObjectProvider<RuntimeBindingApplicationService> runtimeBindingServiceProvider;

    @Autowired
    public ChatRunApplicationService(ChatRunRepository repository, ChatRunCache cache, ChatEventStore eventStore,
                                     PermissionChecker permissionChecker,
                                     SessionRepository sessionRepository,
                                     ChatRunLeaseApplicationService leaseService,
                                     ObjectProvider<ChatRunRecoveryOrchestrator> recoveryOrchestratorProvider,
                                     ObjectProvider<ChatInteractionApplicationService> interactionServiceProvider,
                                     ObjectProvider<RuntimeBindingApplicationService> runtimeBindingServiceProvider) {
        this.repository = repository;
        this.cache = cache;
        this.eventStore = eventStore;
        this.permissionChecker = permissionChecker;
        this.sessionRepository = sessionRepository;
        this.leaseService = leaseService;
        this.recoveryOrchestratorProvider = recoveryOrchestratorProvider;
        this.interactionServiceProvider = interactionServiceProvider;
        this.runtimeBindingServiceProvider = runtimeBindingServiceProvider;
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
        this.interactionServiceProvider = null;
        this.runtimeBindingServiceProvider = null;
    }

    /**
     * 创建 RUNNING 状态的 run 快照。
     */
    public ChatRun createRunning(CreateChatRunContext context) {
        UserContext user = context.user();
        ensureOwnedActiveSession(user, context.sessionId());
        rejectIfActiveRunExists(user, context.sessionId());
        ChatRun saved = insertRunning(context);
        cache.putActive(saved);
        return saved;
    }

    /**
     * 只向数据库插入 RUNNING run；调用方在事务提交后再刷新 Redis active cache。
     */
    ChatRun insertRunning(CreateChatRunContext context) {
        ensureOwnedActiveSession(context.user(), context.sessionId());
        return repository.insert(newRunning(context));
    }

    /**
     * 创建 Interaction continuation run，并在数据库 INSERT 中再次校验 claim 仍归当前 runId 所有。
     */
    public ChatRun createInteractionRunning(CreateChatRunContext context, String interactionId) {
        UserContext user = context.user();
        ensureOwnedActiveSession(user, context.sessionId());
        rejectIfActiveRunExists(user, context.sessionId());
        ChatRun saved = insertInteractionRunning(context, interactionId);
        cache.putActive(saved);
        return saved;
    }

    /**
     * 只在数据库中创建 Interaction continuation run；事务提交后由编排层同步 active cache。
     */
    ChatRun insertInteractionRunning(CreateChatRunContext context, String interactionId) {
        ensureOwnedActiveSession(context.user(), context.sessionId());
        ChatRun run = newRunning(context);
        return repository.insertInteractionContinuationIfClaimed(run, interactionId)
                .orElseThrow(() -> ChatInteractionUnavailableException.alreadyHandled(interactionId));
    }

    private ChatRun newRunning(CreateChatRunContext context) {
        UserContext user = context.user();
        Instant now = Instant.now();
        return new ChatRun(
                context.runId(),
                user.tenantId(),
                user.ownerUserId(),
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
                MessageSkillContext.removeReserved(
                        RelayOutputModeMetadata.removePrivateRunMetadata(
                                AgentDataPersistenceMetadata.removeRunPolicy(context.safeMetadata()))),
                now,
                now
        );
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
            case "run.waiting_user" -> run.waitingUser(event.sequence());
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
     * 仅同步 live-only Runtime 事件中的会话标识，不把未持久化 sequence 写入 run.lastSeq。
     */
    public ChatRun observeLiveOnlyRuntimeState(ChatEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()
                || event.payload() == null) {
            return null;
        }
        Object runtimeSessionId = event.payload().get("runtimeSessionId");
        if (runtimeSessionId == null || String.valueOf(runtimeSessionId).isBlank()) {
            return null;
        }
        return repository.findById(event.runId())
                .map(run -> {
                    if (run.status().terminal() || run.status() == ChatRunStatus.CANCELLING
                            || String.valueOf(runtimeSessionId).equals(run.runtimeSessionId())) {
                        return run;
                    }
                    return save(run.withRuntimeSessionId(String.valueOf(runtimeSessionId)));
                })
                .orElse(null);
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
     * 外部路由进入 run pipeline 后，run.started 会先落库；路由完成后再回填最终路由诊断字段。
     */
    public ChatRun bindResolvedRoute(String runId, RouteTarget route, RuntimeBinding binding) {
        return bindResolvedRoute(runId, route, binding, Map.of());
    }

    public ChatRun bindResolvedRoute(String runId, RouteTarget route, RuntimeBinding binding,
                                     Map<String, Object> metadataOverlay) {
        if (runId == null || runId.isBlank() || route == null) {
            return null;
        }
        return repository.findById(runId)
                .map(run -> updateResolvedRoute(run.withMetadata(metadataOverlay).withResolvedRoute(
                        route.type() == null ? null : route.type().name(),
                        route.selectedAgentCode(),
                        binding == null ? null : binding.provider(),
                        binding == null ? null : binding.runtimeSessionId())))
                .orElse(null);
    }

    /**
     * 在当前 execution 写入权保护下回填最终 Runtime 路由。
     */
    public ChatRun bindResolvedRoute(ChatRun run, RouteTarget route, RuntimeBinding binding,
                                     RunExecutionClaim claim) {
        return bindResolvedRoute(run, route, binding, claim, Map.of());
    }

    /**
     * 在 execution 写入权保护下同时固化最终路由和服务端内部 metadata。
     */
    public ChatRun bindResolvedRoute(ChatRun run, RouteTarget route, RuntimeBinding binding,
                                     RunExecutionClaim claim, Map<String, Object> metadataOverlay) {
        if (run == null || run.id() == null || run.id().isBlank() || route == null || claim == null
                || !run.id().equals(claim.runId())) {
            return null;
        }
        // 使用 admission 已持有的 run 快照，避免 guarded UPDATE 前再执行一次无超时查询。
        return updateResolvedRouteWithExecutionGuard(run.withMetadataSnapshot(resolvedRouteMetadata(
                run, route, metadataOverlay)).withResolvedRoute(
                route.type() == null ? null : route.type().name(),
                route.selectedAgentCode(),
                binding == null ? null : binding.provider(),
                binding == null ? null : binding.runtimeSessionId()), claim);
    }

    /**
     * 按 runId 在 execution 写入权保护下回填最终 Runtime 路由。
     *
     * <p>用于拒答重路由等没有 admission run 快照的流程；查询、加锁和更新共享同一短事务。</p>
     */
    @Transactional(timeoutString =
            "${financeex.runtime-binding.interaction-resume-transaction-timeout-seconds:2}")
    public ChatRun bindResolvedRoute(String runId, RouteTarget route, RuntimeBinding binding,
                                     RunExecutionClaim claim) {
        return bindResolvedRoute(runId, route, binding, claim, Map.of());
    }

    /**
     * 按 runId 在 execution 写入权保护下同时固化最终路由和服务端内部 metadata。
     */
    @Transactional(timeoutString =
            "${financeex.runtime-binding.interaction-resume-transaction-timeout-seconds:2}")
    public ChatRun bindResolvedRoute(String runId, RouteTarget route, RuntimeBinding binding,
                                     RunExecutionClaim claim, Map<String, Object> metadataOverlay) {
        if (runId == null || runId.isBlank() || route == null || claim == null
                || !runId.equals(claim.runId())) {
            return null;
        }
        return repository.findById(runId)
                .map(run -> updateResolvedRouteWithExecutionGuard(run.withMetadataSnapshot(resolvedRouteMetadata(
                        run, route, metadataOverlay)).withResolvedRoute(
                        route.type() == null ? null : route.type().name(),
                        route.selectedAgentCode(),
                        binding == null ? null : binding.provider(),
                        binding == null ? null : binding.runtimeSessionId()), claim))
                .orElse(null);
    }

    private Map<String, Object> resolvedRouteMetadata(
            ChatRun run,
            RouteTarget route,
            Map<String, Object> metadataOverlay) {
        Map<String, Object> metadata = new LinkedHashMap<>(run.metadata());
        if (metadataOverlay != null) {
            metadata.putAll(metadataOverlay);
        }
        return MessageSkillContext.replaceRunSkill(metadata, route.invocationSkillId());
    }

    /**
     * 回填不创建 RuntimeBinding 的路由阶段 provider，例如 intent-agent 澄清等待态。
     */
    public ChatRun bindRuntimeProvider(String runId, String runtimeProvider) {
        if (runId == null || runId.isBlank() || runtimeProvider == null || runtimeProvider.isBlank()) {
            return null;
        }
        return repository.findById(runId)
                .map(run -> save(run.withResolvedRoute(
                        run.routeType(), run.agentCode(), runtimeProvider.trim(), run.runtimeSessionId())))
                .orElse(null);
    }

    /**
     * 接收 stop 请求并写入取消标记。
     */
    public ChatRunStopDecision requestStop(UserContext user, String runId, String reason) {
        return requestStop(user, requireOwnedRun(user, runId), reason);
    }

    /** 使用已完成归属校验的 run 接收 stop，避免协调器分流等待态时重复首查。 */
    public ChatRunStopDecision requestStop(UserContext user, ChatRun run, String reason) {
        if (user == null || run == null
                || !user.tenantId().equals(run.tenantId())
                || !user.ownerUserId().equals(run.userId())) {
            throw new SecurityException("run 不存在或不属于当前用户");
        }
        if (!run.stopRetryable()) {
            return new ChatRunStopDecision(run, false);
        }
        if (run.status() == ChatRunStatus.CANCELLING) {
            cache.markCancellationRequested(run.id());
            return new ChatRunStopDecision(run, true);
        }
        String effectiveReason = reason == null || reason.isBlank() ? "USER_STOP" : reason;
        repository.tryMarkCancelling(new ChatRunRepository.StopClaim(
                run.id(), user.tenantId(), user.ownerUserId(), effectiveReason, Instant.now()));
        ChatRun latest = requireOwnedRun(user, run.id());
        if (latest.status() != ChatRunStatus.CANCELLING) {
            return new ChatRunStopDecision(latest, false);
        }
        cache.markCancellationRequested(run.id());
        cache.putActive(latest);
        return new ChatRunStopDecision(latest, true);
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
     * 同步已由短事务提交的 run 快照到 active-run 热缓存。
     *
     * <p>终态事务直接写 run repository，提交成功后再调用本方法处理 Redis/JVM 缓存，
     * 避免把非数据库资源纳入事务。</p>
     */
    public void synchronizeCommittedRunCache(ChatRun run) {
        if (run == null) {
            return;
        }
        try {
            if (run.status().terminal()) {
                cache.evictActive(run.tenantId(), run.userId(), run.sessionId());
            } else {
                cache.putActive(run);
            }
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_CACHE_SYNC_FAILED,
                            "ChatRun database state committed but active-run cache synchronization failed")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.cache.after-commit")
                    .attribute("runStatus", run.status())
                    .build(), ex);
        }
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
        return repository.findByTenantIdAndUserIdAndId(user.tenantId(), user.ownerUserId(), runId)
                .orElseThrow(() -> new SecurityException("run 不存在或不属于当前用户"));
    }

    /**
     * 批量查询当前用户拥有的 run。该方法用于历史消息装配等只读路径，避免 N+1 查询。
     */
    public Map<String, ChatRun> findOwnedRunsByIds(UserContext user, Collection<String> runIds) {
        permissionChecker.checkChatPermission(user);
        if (runIds == null || runIds.isEmpty()) {
            return Map.of();
        }
        List<String> normalizedRunIds = runIds.stream()
                .filter(runId -> runId != null && !runId.isBlank())
                .distinct()
                .toList();
        if (normalizedRunIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ChatRun> runs = new LinkedHashMap<>();
        repository.findByTenantIdAndUserIdAndIds(user.tenantId(), user.ownerUserId(), normalizedRunIds)
                .forEach(run -> runs.putIfAbsent(run.id(), run));
        return Map.copyOf(runs);
    }

    /**
     * 批量查询当前页存在 active run 的会话标识，不触发租约恢复、Binding 或 Interaction 查询。
     */
    public Set<String> findActiveSessionIds(
            UserContext user, Collection<String> sessionIds) {
        permissionChecker.checkChatPermission(user);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Set.of();
        }
        List<String> normalizedSessionIds = sessionIds.stream()
                .filter(sessionId -> sessionId != null && !sessionId.isBlank())
                .distinct()
                .toList();
        if (normalizedSessionIds.isEmpty()) {
            return Set.of();
        }
        return repository.findActiveSessionIds(
                user.tenantId(), user.ownerUserId(), normalizedSessionIds);
    }

    /**
     * 校验当前会话没有仍在执行的 run。
     *
     * <p>该方法用于创建用户消息前的快速保护，避免 active run 已存在时仍然写入新的用户消息节点。
     * 真正的并发声明仍在 {@link #createRunning} 中通过 Redis set-if-absent 完成。</p>
     */
    public void rejectIfActiveRunExists(UserContext user, String sessionId) {
        findActive(user.tenantId(), user.ownerUserId(), sessionId)
                .ifPresent(active -> {
                    throw new ActiveRunExistsException(sessionId, active.id());
                });
    }

    /**
     * 查询当前会话仍在运行或取消中的 run。
     *
     * <p>删除会话会复用该方法找到 active run 并主动触发 stop。方法会校验会话归属和未删除，
     * 避免跨用户删除或恢复逻辑误操作其他用户的运行态。</p>
     */
    public Optional<ChatRun> findActiveRun(UserContext user, String sessionId) {
        ensureOwnedSession(user, sessionId);
        return findActive(user.tenantId(), user.ownerUserId(), sessionId);
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
        if ("run.completed".equals(event.type()) || "run.failed".equals(event.type())
                || "run.waiting_user".equals(event.type())) {
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
        long latestSeq = eventStore.findLatestSeqByOwnerAndSession(user.tenantId(), user.ownerUserId(), sessionId);
        Optional<ChatRun> active = findActive(user.tenantId(), user.ownerUserId(), sessionId);
        if (active.isPresent() && leaseService != null && leaseService.isLeaseExpired(active.get().id())) {
            ChatRunRecoveryOrchestrator orchestrator = recoveryOrchestratorProvider == null
                    ? null
                    : recoveryOrchestratorProvider.getIfAvailable();
            if (orchestrator != null) {
                orchestrator.recoverExpiredRun(active.get().id());
                latestSeq = eventStore.findLatestSeqByOwnerAndSession(user.tenantId(), user.ownerUserId(), sessionId);
                active = findActive(user.tenantId(), user.ownerUserId(), sessionId);
            }
        }
        long currentLatestSeq = latestSeq;
        BindingSummary bindingSummary = bindingSummary(user, sessionId);
        return active
                .map(run -> activeStatus(sessionId, currentLatestSeq, run, bindingSummary))
                .orElseGet(() -> waitingStatus(user, sessionId, currentLatestSeq, bindingSummary));
    }

    private ChatStreamStatus activeStatus(String sessionId, long latestSeq, ChatRun run,
                                          BindingSummary bindingSummary) {
        ActiveContinuationSummary continuation = activeContinuationSummary(run);
        return new ChatStreamStatus(sessionId, latestSeq, run.id(), run.status(),
                ChatStreamTopics.runTopic(run.id()), run.firstSeq(), run.lastSeq(), run.cancellable(),
                false, null, continuation.interactionId(), continuation.interactionType(),
                continuation.assistantMessageId(), null, null, null, null, null, null,
                bindingSummary.provider(), bindingSummary.targetType(), bindingSummary.targetId(),
                bindingSummary.intentCode(), bindingSummary.intentName(), bindingSummary.routeSource(),
                bindingSummary.updatedAt(), bindingSummary.agentMode());
    }

    private ActiveContinuationSummary activeContinuationSummary(ChatRun run) {
        if (run == null || InteractionMessageStrategy.newTurn(run)) {
            return ActiveContinuationSummary.empty();
        }
        String interactionId = metadataText(run, INTERACTION_ID_METADATA);
        String interactionType = metadataText(run, INTERACTION_TYPE_METADATA);
        String assistantMessageId = metadataText(run, INTERACTION_ASSISTANT_MESSAGE_ID_METADATA);
        if (interactionId == null || interactionType == null || assistantMessageId == null) {
            return ActiveContinuationSummary.empty();
        }
        return new ActiveContinuationSummary(interactionId, interactionType, assistantMessageId);
    }

    private String metadataText(ChatRun run, String key) {
        if (run == null || run.metadata() == null || key == null) {
            return null;
        }
        Object value = run.metadata().get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private ChatStreamStatus waitingStatus(UserContext user, String sessionId, long latestSeq,
                                           BindingSummary bindingSummary) {
        ChatInteractionApplicationService interactionService = interactionServiceProvider == null ? null : interactionServiceProvider.getIfAvailable();
        if (interactionService == null) {
            return new ChatStreamStatus(sessionId, latestSeq, null, null, null, null, null,
                    false, false, null, null, null, null, null, null, null, null, null, null,
                    bindingSummary.provider(), bindingSummary.targetType(), bindingSummary.targetId(),
                    bindingSummary.intentCode(), bindingSummary.intentName(), bindingSummary.routeSource(),
                    bindingSummary.updatedAt(), bindingSummary.agentMode());
        }
        return interactionService.findWaiting(user, sessionId)
                .map(request -> new ChatStreamStatus(sessionId, latestSeq, null, null, null, null, null,
                        false, true, request.sourceRunId(), request.id(), request.interactionType().name(),
                        request.assistantMessageId(), request.expiresAt(),
                        autoSelectAt(request), autoSelectTimeoutMs(request),
                        payloadInstant(request, "autoActionAt"),
                        payloadLong(request, "autoActionTimeoutMs"),
                        payloadText(request, "autoActionType"),
                        bindingSummary.provider(), bindingSummary.targetType(), bindingSummary.targetId(),
                        bindingSummary.intentCode(), bindingSummary.intentName(), bindingSummary.routeSource(),
                        bindingSummary.updatedAt(), bindingSummary.agentMode()))
                .orElseGet(() -> new ChatStreamStatus(sessionId, latestSeq, null, null, null, null, null,
                        false, false, null, null, null, null, null, null, null, null, null, null,
                        bindingSummary.provider(), bindingSummary.targetType(), bindingSummary.targetId(),
                        bindingSummary.intentCode(), bindingSummary.intentName(), bindingSummary.routeSource(),
                        bindingSummary.updatedAt(), bindingSummary.agentMode()));
    }

    private record ActiveContinuationSummary(
            String interactionId,
            String interactionType,
            String assistantMessageId) {
        private static ActiveContinuationSummary empty() {
            return new ActiveContinuationSummary(null, null, null);
        }
    }

    private Instant autoSelectAt(com.huawei.it.ex.one.domain.chat.ChatInteractionRequest request) {
        return payloadInstant(request, "autoSelectAt");
    }

    private Instant payloadInstant(com.huawei.it.ex.one.domain.chat.ChatInteractionRequest request,
                                   String key) {
        Object value = payloadValue(request, key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (java.time.format.DateTimeParseException ignored) {
            return null;
        }
    }

    private Long autoSelectTimeoutMs(com.huawei.it.ex.one.domain.chat.ChatInteractionRequest request) {
        return payloadLong(request, "autoSelectTimeoutMs");
    }

    private Long payloadLong(com.huawei.it.ex.one.domain.chat.ChatInteractionRequest request,
                             String key) {
        Object value = payloadValue(request, key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String payloadText(com.huawei.it.ex.one.domain.chat.ChatInteractionRequest request,
                               String key) {
        Object value = payloadValue(request, key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private Object payloadValue(com.huawei.it.ex.one.domain.chat.ChatInteractionRequest request,
                                String key) {
        return request == null || request.requestPayload() == null ? null : request.requestPayload().get(key);
    }

    private BindingSummary bindingSummary(UserContext user, String sessionId) {
        RuntimeBindingApplicationService runtimeBindingService = runtimeBindingServiceProvider == null
                ? null
                : runtimeBindingServiceProvider.getIfAvailable();
        if (runtimeBindingService == null) {
            return BindingSummary.empty();
        }
        return runtimeBindingService.findActiveBySession(user.tenantId(), user.ownerUserId(), sessionId)
                .map(this::toBindingSummary)
                .orElseGet(BindingSummary::empty);
    }

    private BindingSummary toBindingSummary(RuntimeBinding binding) {
        Map<String, Object> metadata = binding.metadata();
        String targetType = RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(binding.provider())
                ? "DOMAIN_AGENT"
                : "AGENT_RUNTIME";
        return new BindingSummary(
                binding.provider(),
                targetType,
                stringValue(metadata.get("domainAgentId")),
                stringValue(metadata.get("intentCode")),
                stringValue(metadata.get("intentName")),
                stringValue(metadata.get("routeSource")),
                binding.updatedAt(),
                RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(binding.provider())
                        ? AgentModeBindingContext.fromBinding(binding)
                        : null);
    }

    private String stringValue(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private record BindingSummary(String provider, String targetType, String targetId, String intentCode,
                                  String intentName, String routeSource, Instant updatedAt,
                                  AgentModeProfile agentMode) {
        private static BindingSummary empty() {
            return new BindingSummary(null, null, null, null, null, null, null, null);
        }
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
        synchronizeActiveRunCache(saved);
        return saved;
    }

    private ChatRun updateResolvedRoute(ChatRun run) {
        ChatRun saved = repository.updateResolvedRoute(run);
        synchronizeActiveRunCache(saved);
        return saved;
    }

    private ChatRun updateResolvedRouteWithExecutionGuard(ChatRun run, RunExecutionClaim claim) {
        ChatRun saved = repository.updateResolvedRouteWithExecutionGuard(run, claim);
        synchronizeActiveRunCache(saved);
        return saved;
    }

    private void synchronizeActiveRunCache(ChatRun saved) {
        if (saved.status().terminal()) {
            cache.evictActive(saved.tenantId(), saved.userId(), saved.sessionId());
        } else {
            cache.putActive(saved);
        }
    }

    private void ensureOwnedSession(UserContext user, String sessionId) {
        loadOwnedNotDeletedSession(user, sessionId);
    }

    private void ensureOwnedActiveSession(UserContext user, String sessionId) {
        ChatSession session = loadOwnedNotDeletedSession(user, sessionId);
        if (!SESSION_STATUS_ACTIVE.equals(session.status())) {
            throw new IllegalStateException("会话不可用: " + sessionId);
        }
    }

    private ChatSession loadOwnedNotDeletedSession(UserContext user, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        ChatSession session = sessionRepository.findByTenantIdAndUserIdAndId(user.tenantId(), user.ownerUserId(), sessionId)
                .orElseThrow(() -> new SecurityException("会话不存在或不属于当前用户"));
        if (SESSION_STATUS_DELETED.equals(session.status())) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return session;
    }

}
