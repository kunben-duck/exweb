package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.application.integration.agent.AgentModeBindingContext;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.routing.RuntimeProfile;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.it.ex.one.domain.runtime.RuntimeProfileMetadata;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 下游多轮绑定应用服务。
 *
 * <p>Relay Runtime 和 DomainAgent 都通过 RuntimeBinding 维持会话上下文。状态变更先写数据库，
 * 再刷新或删除 Redis 热缓存。</p>
 */
@Service
public class RuntimeBindingApplicationService {
    /** 当前上线版本默认 AgentRuntime provider 编码。 */
    public static final String DEFAULT_RUNTIME_PROVIDER = "relay";
    /** 财经领域 Agent 绑定 provider 编码。 */
    public static final String DOMAIN_AGENT_PROVIDER = "domain-agent";
    private static final String RUNTIME_SESSION_ESTABLISHED = "runtimeSessionEstablished";

    private final RuntimeBindingRepository repository;
    private final RuntimeBindingCache cache;
    private final IdGenerator idGenerator;
    private final Duration ttl;
    private final String runtimeProvider;
    private final String delegateAppMode;
    private final String domainExpertAppMode;

    /**
     * 创建 Runtime 绑定服务。
     *
     * @param repository RuntimeBinding 事实源仓储。
     * @param cache RuntimeBinding Redis 热缓存。
     * @param idGenerator 统一 ID 生成器。
     * @param ttl Runtime 绑定可续接窗口。
     * @param runtimeProvider 默认 AgentRuntime provider 编码。
     */
    public RuntimeBindingApplicationService(RuntimeBindingRepository repository, RuntimeBindingCache cache,
                                            IdGenerator idGenerator, Duration ttl, String runtimeProvider) {
        this(repository, cache, idGenerator, ttl, runtimeProvider,
                "delegate", "domain_expert");
    }

    @Autowired
    public RuntimeBindingApplicationService(
            RuntimeBindingRepository repository,
            RuntimeBindingCache cache,
            IdGenerator idGenerator,
            @Value("${financeex.runtime-binding.ttl:0s}") Duration ttl,
            @Value("${financeex.agent-runtime.default-provider:relay}") String runtimeProvider,
            @Value("${financeex.agent-runtime.relay.websocket.app-mode:delegate}") String delegateAppMode,
            @Value("${financeex.agent-runtime.relay.domain-expert.app-mode:domain_expert}") String domainExpertAppMode) {
        this.repository = repository;
        this.cache = cache;
        this.idGenerator = idGenerator;
        this.ttl = RuntimeBindingExpirationPolicy.normalize(ttl);
        this.runtimeProvider = normalizeProvider(runtimeProvider);
        this.delegateAppMode = requireText(delegateAppMode, "Delegate Relay appMode");
        this.domainExpertAppMode = requireText(domainExpertAppMode, "Domain expert Relay appMode");
    }

    /**
     * 查询当前会话 active RuntimeBinding，优先 Redis，miss 后回源数据库并回填。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return 当前可续接 Runtime 绑定。
     */
    public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId, String leafMessageId) {
        Instant now = Instant.now();
        Optional<RuntimeBinding> cached = cache.get(tenantId, userId, sessionId, leafMessageId);
        if (cached.isPresent()) {
            if (routableForCurrentProvider(cached.get(), now)) {
                return cached;
            }
            // Redis 是热缓存，不是事实源；发现过期、不可路由或 provider 不匹配的缓存后立即清理，
            // 避免 Runtime 切换后把旧实现的 runtimeSessionId 误传给新 Runtime。
            cache.evict(tenantId, userId, sessionId);
        }
        Optional<RuntimeBinding> persisted = repository.findActive(tenantId, userId, sessionId, runtimeProvider, leafMessageId)
                .filter(binding -> routableForCurrentProvider(binding, now));
        persisted.ifPresent(cache::put);
        return persisted;
    }

    /**
     * 解析本轮 AgentRuntime 应使用的会话绑定。
     *
     * <p>这里按会话维度复用 active binding，不再因消息树 leaf 切换而创建新的下游 Runtime
     * session。Relay 正常完成后只释放自动路由，并以 RESUMABLE 状态保留其真实 session；只有
     * 路由再次选择 Relay 时才恢复该 binding。</p>
     */
    public RuntimeBindingResolution resolveForRun(String tenantId, String userId, String sessionId,
                                                  String runId, String leafMessageId) {
        return resolveForProfile(new ProfiledRunBindingRequest(
                tenantId, userId, sessionId, runId, leafMessageId, RuntimeProfile.DELEGATE, null));
    }

    /**
     * 按调用档案解析本轮 Relay Binding，避免 Delegate 与 Domain Expert 复用同一 Runtime session。
     */
    public RuntimeBindingResolution resolveForProfile(ProfiledRunBindingRequest request) {
        return resolveForProfile(request, Map.of());
    }

    /**
     * 解析前端固定选择的Relay专家Binding，并在同一次Binding保存中固化展示摘要。
     */
    @Transactional(timeoutString =
            "${financeex.runtime-binding.interaction-resume-transaction-timeout-seconds:2}")
    public RuntimeBindingResolution resolvePinnedDomainExpertForRun(
            ProfiledRunBindingRequest request,
            Map<String, Object> metadataOverlay,
            RunExecutionClaim claim) {
        if (request == null || request.runtimeProfile() != RuntimeProfile.DOMAIN_EXPERT) {
            throw new IllegalArgumentException("Pinned domain expert requires DOMAIN_EXPERT profile");
        }
        if (!repository.lockRunExecutionForBindingMutation(
                request.tenantId(), request.userId(), request.sessionId(), claim)) {
            throw new ChatEventAppendRejectedException(
                    "Pinned domain expert Binding 被 run/execution 栅栏拒绝: runId="
                            + (claim == null ? null : claim.runId()));
        }
        cancelActiveExceptPinnedDomainExpert(
                request.tenantId(), request.userId(), request.sessionId(), request.runtimeRoleName());
        return resolveForProfile(request, metadataOverlay);
    }

    private RuntimeBindingResolution resolveForProfile(ProfiledRunBindingRequest request,
                                                       Map<String, Object> metadataOverlay) {
        if (request == null) {
            throw new IllegalArgumentException("ProfiledRunBindingRequest must not be null");
        }
        String tenantId = request.tenantId();
        String userId = request.userId();
        String sessionId = request.sessionId();
        String runId = request.runId();
        String leafMessageId = request.leafMessageId();
        Instant now = Instant.now();
        RuntimeProfileMetadata.Snapshot desiredProfile = configuredProfile(
                request.runtimeProfile(), request.runtimeRoleName());
        List<RuntimeBinding> activeBindings = repository.findActiveBySession(tenantId, userId, sessionId, runtimeProvider)
                .stream()
                .filter(binding -> routableForCurrentProvider(binding, now))
                .filter(binding -> matchingProfile(binding, desiredProfile))
                .sorted(Comparator.comparing(RuntimeBinding::updatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .toList();
        if (!activeBindings.isEmpty()) {
            RuntimeBinding previous = activeBindings.getFirst();
            RuntimeBinding selected = touchForRun(
                    previous.withMetadata(bindingMetadata(previous, desiredProfile, metadataOverlay)), runId);
            cancelDuplicateBindings(activeBindings, selected);
            cache.put(selected);
            return new RuntimeBindingResolution(selected, RuntimeSessionMode.RESUME, previous);
        }
        List<RuntimeBinding> resumableBindings = repository
                .findResumableBySession(tenantId, userId, sessionId, runtimeProvider)
                .stream()
                .filter(this::resumableForCurrentProvider)
                .filter(binding -> matchingProfile(binding, desiredProfile))
                .sorted(Comparator.comparing(RuntimeBinding::updatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .toList();
        if (!resumableBindings.isEmpty()) {
            RuntimeBinding previous = resumableBindings.getFirst();
            RuntimeBinding selected = activateResumableForRun(
                    previous.withMetadata(bindingMetadata(previous, desiredProfile, metadataOverlay)),
                    runId, leafMessageId);
            cancelDuplicateBindings(resumableBindings, selected);
            return new RuntimeBindingResolution(selected, RuntimeSessionMode.RESUME, previous);
        }
        RuntimeBinding created = create(new RuntimeBindingCreateCommand(
                tenantId, userId, sessionId, runtimeProvider, runId, leafMessageId, sessionId,
                bindingMetadata(null, desiredProfile, metadataOverlay)));
        return new RuntimeBindingResolution(created, RuntimeSessionMode.NEW);
    }

    /** 带 Relay 调用档案的 Binding 解析请求。 */
    public record ProfiledRunBindingRequest(
            String tenantId,
            String userId,
            String sessionId,
            String runId,
            String leafMessageId,
            RuntimeProfile runtimeProfile,
            String runtimeRoleName) {
        public ProfiledRunBindingRequest {
            runtimeProfile = runtimeProfile == null ? RuntimeProfile.DELEGATE : runtimeProfile;
            runtimeRoleName = normalizeText(runtimeRoleName);
            if (runtimeProfile == RuntimeProfile.DOMAIN_EXPERT && runtimeRoleName == null) {
                throw new IllegalArgumentException("Domain expert runtimeRoleName must not be blank");
            }
            if (runtimeProfile != RuntimeProfile.DOMAIN_EXPERT) {
                runtimeRoleName = null;
            }
        }

        private static String normalizeText(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim();
            return normalized.isEmpty() ? null : normalized;
        }
    }

    /**
     * 查询根路径 RuntimeBinding。
     */
    public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId) {
        return findActive(tenantId, userId, sessionId, null);
    }

    /**
     * 按会话维度查询 active RuntimeBinding。
     *
     * <p>普通继续提问优先复用会话下最新的 active binding。Relay 正常完成后不保留 active binding；
     * 如果仍能查到 relay binding，说明当前 Relay 任务尚未完成或处于等待用户输入状态。</p>
     */
    public Optional<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId) {
        Instant now = Instant.now();
        List<RuntimeBinding> activeBindings = repository.findActiveBySession(tenantId, userId, sessionId)
                .stream()
                .filter(binding -> binding.routableAt(now))
                .sorted(Comparator.comparing(RuntimeBinding::updatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .toList();
        if (activeBindings.isEmpty()) {
            return Optional.empty();
        }
        RuntimeBinding selected = activeBindings.getFirst();
        cancelDuplicateBindings(activeBindings, selected);
        cache.put(selected);
        return Optional.of(selected);
    }

    public Optional<RuntimeBinding> findActiveDomainAgentBySession(String tenantId, String userId, String sessionId) {
        Instant now = Instant.now();
        return repository.findActiveBySession(tenantId, userId, sessionId, DOMAIN_AGENT_PROVIDER).stream()
                .filter(binding -> binding.routableAt(now))
                .sorted(Comparator.comparing(RuntimeBinding::updatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .findFirst();
    }

    /**
     * 加载拒答重路由所引用的历史 DomainAgent binding。
     *
     * <p>该 binding 只用于恢复拒答时的 Agent、routeSource 和会话上下文，不代表仍可路由。
     * 因此这里按 ID 和归属读取数据库事实，允许 ACTIVE/CANCELLED，也不检查 expiresAt、访问缓存或刷新状态。</p>
     */
    public RuntimeBinding loadDomainAgentForReroute(String tenantId, String userId, String sessionId,
                                                     String bindingId, String expectedDomainAgentId) {
        if (bindingId == null || bindingId.isBlank()) {
            throw new IllegalStateException("拒答重路由上下文缺少 RuntimeBinding ID");
        }
        if (expectedDomainAgentId == null || expectedDomainAgentId.isBlank()) {
            throw new IllegalStateException("拒答重路由上下文缺少 DomainAgent ID");
        }
        return repository.findById(bindingId)
                .filter(binding -> tenantId.equals(binding.tenantId()))
                .filter(binding -> userId.equals(binding.userId()))
                .filter(binding -> sessionId.equals(binding.chatSessionId()))
                .filter(binding -> DOMAIN_AGENT_PROVIDER.equals(binding.provider()))
                .filter(binding -> binding.status() == RuntimeBindingStatus.ACTIVE
                        || binding.status() == RuntimeBindingStatus.CANCELLED)
                .filter(binding -> expectedDomainAgentId.equals(metadataText(binding, "domainAgentId")))
                .orElseThrow(() -> new IllegalStateException(
                        "拒答重路由 RuntimeBinding 不存在、归属不匹配或 Agent 已变化: " + bindingId));
    }

    /**
     * 为复杂任务创建新的 AgentRuntime 绑定。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @param runId 本轮运行标识。
     * @return 已保存的 Runtime 绑定。
     */
    public RuntimeBinding create(String tenantId, String userId, String sessionId, String runId, String leafMessageId) {
        return create(new RuntimeBindingCreateCommand(
                tenantId, userId, sessionId, runtimeProvider, runId, leafMessageId, sessionId, Map.of()));
    }

    private RuntimeBinding create(RuntimeBindingCreateCommand command) {
        return save(newBinding(command));
    }

    private RuntimeBinding newBinding(RuntimeBindingCreateCommand command) {
        Instant now = Instant.now();
        String id = idGenerator.newId("runtime_binding",
                IdGenerateContext.of(command.tenantId(), command.userId(), command.sessionId()));
        return new RuntimeBinding(id, command.tenantId(), command.userId(), command.sessionId(),
                normalizeProvider(command.provider()), command.leafMessageId(),
                blankToDefault(command.runtimeSessionId(), command.sessionId()), RuntimeBindingStatus.ACTIVE,
                command.runId(), expiresAt(command.provider(), false), now, now, command.metadata());
    }

    public RuntimeBinding create(String tenantId, String userId, String sessionId, String runId) {
        return create(tenantId, userId, sessionId, runId, null);
    }

    public RuntimeBinding bindDomainAgentForRun(DomainAgentBindingCommand command) {
        validateDomainAgentBindingCommand(command);
        cancelActive(command.tenantId(), command.userId(), command.sessionId());
        return save(newDomainAgentBinding(command));
    }

    /**
     * Atomically switches a confirmed route interaction to a new DomainAgent behind the current execution fence.
     * Redis is synchronized by the caller only after this transaction commits.
     */
    @Transactional(timeoutString =
            "${financeex.runtime-binding.interaction-resume-transaction-timeout-seconds:2}")
    public RuntimeBinding switchDomainAgentForInteraction(
            ChatInteractionRequest interaction,
            DomainAgentBindingCommand command,
            RunExecutionClaim claim) {
        validateDomainAgentRouteSwitch(interaction, command, claim);
        if (!repository.lockRunExecutionForBindingMutation(
                command.tenantId(), command.userId(), command.sessionId(), claim)) {
            throw new ChatEventAppendRejectedException(
                    "DomainAgent route-switch Binding 被 run/execution 栅栏拒绝: runId="
                            + claim.runId());
        }

        RuntimeBinding source = loadInteractionBinding(interaction);
        validateDomainAgentRouteSwitchSource(interaction, source);
        RuntimeBinding candidate = newDomainAgentBinding(command);

        RuntimeBinding cancelledSource = routeSwitchCancelledSource(
                source, interaction, command.runId());
        repository.save(cancelledSource);
        cancelOtherActiveBindingsForRouteSwitch(candidate, source.id());
        return repository.save(candidate);
    }

    /** Prepares a new DomainAgent binding without changing the database or Redis. */
    public DeferredDomainAgentBinding prepareDomainAgentForRun(DomainAgentBindingCommand command) {
        validateDomainAgentBindingCommand(command);
        return new DeferredDomainAgentBinding(newDomainAgentBinding(command), null);
    }

    /** Prepares an ACTIVE DomainAgent continuation without refreshing persisted run ownership. */
    public DeferredDomainAgentBinding prepareActiveDomainAgentForRun(
            RuntimeBinding binding,
            String runId,
            AgentModeProfile agentMode) {
        if (binding == null
                || !DOMAIN_AGENT_PROVIDER.equals(binding.provider())
                || binding.status() != RuntimeBindingStatus.ACTIVE) {
            throw new IllegalArgumentException("Deferred DomainAgent continuation requires an ACTIVE binding");
        }
        RuntimeBinding candidate = binding
                .withRun(runId, expiresAt(binding.provider(), false))
                .withMetadata(AgentModeBindingContext.apply(binding.metadata(), agentMode));
        return new DeferredDomainAgentBinding(candidate, binding);
    }

    /**
     * Persists a validated DomainAgent binding immediately before Runtime subscription.
     * Cache synchronization is deliberately performed by the caller after this transaction commits.
     */
    @Transactional(timeoutString =
            "${financeex.runtime-binding.interaction-resume-transaction-timeout-seconds:2}")
    public DeferredDomainAgentBindingActivation activateDeferredDomainAgentForRuntime(
            DeferredDomainAgentBinding deferred,
            RunExecutionClaim claim) {
        RuntimeBinding candidate = requireDeferredCandidate(deferred);
        if (!repository.lockRunExecutionForBindingMutation(
                candidate.tenantId(), candidate.userId(), candidate.chatSessionId(), claim)) {
            throw new ChatEventAppendRejectedException(
                    "Deferred DomainAgent Binding 被 run/execution 栅栏拒绝: runId="
                            + (claim == null ? null : claim.runId()));
        }
        List<AdmissionCancellation> cancellations = cancelOtherActiveBindingsForDeferred(candidate);
        RuntimeBinding saved = saveDeferredCandidate(deferred, candidate);
        return new DeferredDomainAgentBindingActivation(saved, deferred.previousBinding(), cancellations);
    }

    /** Clears stale leaf cache keys before exposing an atomically activated DomainAgent binding. */
    public void synchronizeDeferredDomainAgentActivation(RuntimeBinding binding) {
        if (binding == null) {
            return;
        }
        cache.evict(binding.tenantId(), binding.userId(), binding.chatSessionId());
        cache.put(binding);
    }

    public RuntimeBinding touchDomainAgentForRun(RuntimeBinding binding, String runId,
                                                 String domainAgentId, String routeSource,
                                                 Map<String, Object> intentMetadata) {
        if (binding == null) {
            return null;
        }
        Map<String, Object> metadata = domainAgentMetadata(domainAgentId, routeSource, intentMetadata,
                binding.metadata(), null);
        return save(binding.withMetadata(metadata).withRun(runId, expiresAt(binding.provider(), false)));
    }

    /** 在复用当前 DomainAgent binding 时，仅按本次显式请求更新模式记录。 */
    public RuntimeBinding touchDomainAgentForRun(RuntimeBinding binding, String runId,
                                                 AgentModeProfile agentMode) {
        if (binding == null) {
            return null;
        }
        if (!DOMAIN_AGENT_PROVIDER.equals(binding.provider())) {
            return touchForRun(binding, runId);
        }
        RuntimeBinding next = binding.withRun(runId, expiresAt(binding.provider(), false));
        return save(next.withMetadata(AgentModeBindingContext.apply(next.metadata(), agentMode)));
    }

    /**
     * 刷新 Runtime 绑定活跃窗口。
     *
     * @param binding 当前 Runtime 绑定。
     * @param runId 本轮运行标识。
     * @return 续期后的 Runtime 绑定。
     */
    public RuntimeBinding touchForRun(RuntimeBinding binding, String runId) {
        if (binding == null) {
            return null;
        }
        RuntimeBinding next = binding.withRun(runId, expiresAt(binding.provider(), relaySessionEstablished(binding)));
        return save(next);
    }

    /**
     * Interaction 续接必须回到创建等待态时的真实 RuntimeBinding。
     *
     * <p>这里不创建临时 binding；同时刷新本轮 runId、leaf 和 runtimeSessionId。已经建立的 Relay
     * session 不再设置业务过期时间。</p>
     */
    public RuntimeBinding resumeForInteraction(ChatInteractionRequest request, String runId) {
        return resumeForInteraction(request, runId, null);
    }

    public RuntimeBinding resumeForInteraction(ChatInteractionRequest request, String runId,
                                               AgentModeProfile agentMode) {
        if (request == null) {
            throw new IllegalArgumentException("Interaction 请求不能为空");
        }
        RuntimeBinding binding = loadInteractionBinding(request);
        RuntimeBinding next = withRuntimeSessionId(binding, request.runtimeSessionId());
        RuntimeBinding updated = DOMAIN_AGENT_PROVIDER.equals(next.provider())
                ? next.withMetadata(AgentModeBindingContext.apply(next.metadata(), agentMode))
                : next;
        return touchAndMoveToLeaf(updated, runId, request.assistantMessageId());
    }

    /**
     * 在当前 execution 写入权保护下恢复等待态 Relay Binding。
     *
     * <p>该路径只接受 run-A 保存的 ACTIVE Relay Binding，不允许把 CANCELLED Binding
     * 或其他 provider 重新激活。</p>
     */
    public RuntimeBinding resumeRelayForInteraction(ChatInteractionRequest request,
                                                    String runId,
                                                    RunExecutionClaim claim) {
        if (request == null || runId == null || runId.isBlank() || claim == null
                || !runId.equals(claim.runId())) {
            throw new IllegalArgumentException("Relay Interaction Binding 续接参数不完整");
        }
        RuntimeBinding binding = loadInteractionBinding(request);
        validateRelayInteractionBinding(request, binding);
        RuntimeBinding next = binding.withRun(runId, null);
        if (request.assistantMessageId() != null && !request.assistantMessageId().isBlank()
                && !request.assistantMessageId().equals(next.leafMessageId())) {
            next = next.withLeafMessageId(request.assistantMessageId());
        }
        RuntimeBinding resumed = repository.resumeInteractionWithExecutionGuard(
                        next, request.sourceRunId(), claim)
                .orElseThrow(() -> new ChatEventAppendRejectedException(
                        "Relay Interaction Binding 被 run/execution 栅栏拒绝: runId=" + runId));
        synchronizeCache(resumed);
        return resumed;
    }

    /**
     * run-B 尚未订阅 Relay 时只回退 Binding 的 lastRunId，不销毁可恢复的 Relay session。
     */
    public boolean restoreUnstartedRelayInteraction(RuntimeBinding binding,
                                                    String continueRunId,
                                                    String sourceRunId) {
        if (binding == null || continueRunId == null || continueRunId.isBlank()
                || sourceRunId == null || sourceRunId.isBlank()) {
            return false;
        }
        boolean restored = repository.restoreInteractionResume(binding.id(), continueRunId, sourceRunId);
        if (restored) {
            cache.evict(binding.tenantId(), binding.userId(), binding.chatSessionId());
        }
        return restored;
    }

    /**
     * 刷新绑定活跃窗口，并移动到给定 leaf。
     *
     * <p>与 {@link #moveToLeaf(RuntimeBinding, String)} 不同，本方法即使 leaf 没变也会保存，
     * 因为 Interaction 续接完成时需要刷新绑定生命周期和 lastRunId。</p>
     */
    public RuntimeBinding touchAndMoveToLeaf(RuntimeBinding binding, String runId, String leafMessageId) {
        if (binding == null) {
            return null;
        }
        RuntimeBinding next = binding.withRun(runId,
                expiresAt(binding.provider(), relaySessionEstablished(binding)));
        if (leafMessageId != null && !leafMessageId.isBlank()
                && !leafMessageId.equals(next.leafMessageId())) {
            next = next.withLeafMessageId(leafMessageId);
        }
        return save(next);
    }

    /**
     * Runtime 正常完成后更新绑定生命周期。DomainAgent 保持 active；Relay 只释放自动路由，
     * 仍永久保留实际 runtimeSessionId 供后续重新路由时恢复。
     */
    public RuntimeBinding completeAfterRun(RuntimeBinding binding, String runId, String leafMessageId) {
        if (binding == null) {
            return null;
        }
        if (DOMAIN_AGENT_PROVIDER.equals(binding.provider()) || isPinnedDomainExpert(binding)) {
            return touchAndMoveToLeaf(binding, runId, leafMessageId);
        }
        RuntimeBinding next = markRelaySessionEstablished(binding, binding.runtimeSessionId())
                .withRun(runId, null);
        if (leafMessageId != null && !leafMessageId.isBlank()
                && !leafMessageId.equals(next.leafMessageId())) {
            next = next.withLeafMessageId(leafMessageId);
        }
        return save(next.withStatus(RuntimeBindingStatus.RESUMABLE));
    }

    /**
     * Completes an async DomainAgent binding without writing a stale binding snapshot over a later run.
     */
    public boolean completeDomainAgentAfterAsyncRun(
            String tenantId,
            String userId,
            String sessionId,
            String runId,
            String leafMessageId) {
        RuntimeBinding binding = repository.findActiveBySession(
                        tenantId, userId, sessionId, DOMAIN_AGENT_PROVIDER).stream()
                .filter(candidate -> runId.equals(candidate.lastRunId()))
                .sorted(Comparator.comparing(RuntimeBinding::updatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .findFirst()
                .orElse(null);
        if (binding == null) {
            return false;
        }
        boolean completed = repository.completeActiveDomainAgentForRun(
                binding, runId, leafMessageId,
                expiresAt(DOMAIN_AGENT_PROVIDER, false), Instant.now());
        if (completed) {
            // The database update intentionally changes only leaf/expiry; evict instead of caching the old snapshot.
            cache.evict(tenantId, userId, sessionId);
        }
        return completed;
    }

    /**
     * Runtime 完成后把绑定移动到新 assistant 叶子。
     */
    public RuntimeBinding moveToLeaf(RuntimeBinding binding, String leafMessageId) {
        if (binding == null || leafMessageId == null || leafMessageId.isBlank()
                || leafMessageId.equals(binding.leafMessageId())) {
            return binding;
        }
        return save(binding.withLeafMessageId(leafMessageId));
    }

    /**
     * 取消当前 active Runtime 绑定。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     */
    public void cancelActive(String tenantId, String userId, String sessionId) {
        List<RuntimeBinding> active = repository.findActiveBySession(tenantId, userId, sessionId);
        if (active.isEmpty()) {
            active = repository.findActiveBySession(tenantId, userId, sessionId, runtimeProvider);
        }
        active.forEach(binding -> save(binding.withStatus(RuntimeBindingStatus.CANCELLED)));
        cache.evict(tenantId, userId, sessionId);
    }

    /**
     * 在 DomainAgent 直连 admission 事务内取消会话当前 ACTIVE binding。
     *
     * <p>该方法只更新数据库事实，不访问 Redis；调用方必须在事务提交后异步同步返回的 binding
     * 快照。RESUMABLE Relay 不在查询范围内，因此会继续保留。</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RuntimeBinding> cancelActiveForAdmission(String tenantId, String userId, String sessionId) {
        return cancelActiveForAdmissionWithSnapshots(tenantId, userId, sessionId).stream()
                .map(AdmissionCancellation::cancelled)
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<AdmissionCancellation> cancelActiveForAdmissionWithSnapshots(
            String tenantId,
            String userId,
            String sessionId) {
        Map<String, RuntimeBinding> active = activeBindingsForAdmission(tenantId, userId, sessionId);
        List<AdmissionCancellation> cancelled = new ArrayList<>();
        for (RuntimeBinding binding : active.values()) {
            if (binding.status() == RuntimeBindingStatus.ACTIVE) {
                RuntimeBinding cancelledBinding = repository.save(
                        binding.withStatus(RuntimeBindingStatus.CANCELLED));
                cancelled.add(new AdmissionCancellation(binding, cancelledBinding));
            }
        }
        return List.copyOf(cancelled);
    }

    /**
     * 直连专家准入时保留同一固定专家，其余ACTIVE Binding仍在同一事务内取消。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RuntimeBinding> cancelActiveForAdmissionExceptPinnedDomainExpert(
            String tenantId, String userId, String sessionId, String roleName) {
        return cancelActiveForAdmissionExceptPinnedDomainExpertWithSnapshots(
                tenantId, userId, sessionId, roleName).stream()
                .map(AdmissionCancellation::cancelled)
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<AdmissionCancellation> cancelActiveForAdmissionExceptPinnedDomainExpertWithSnapshots(
            String tenantId,
            String userId,
            String sessionId,
            String roleName) {
        Map<String, RuntimeBinding> active = activeBindingsForAdmission(tenantId, userId, sessionId);
        RuntimeBinding preserved = newestPinnedDomainExpert(active.values().stream().toList(), roleName);
        List<AdmissionCancellation> cancelled = new ArrayList<>();
        for (RuntimeBinding binding : active.values()) {
            if (binding.status() == RuntimeBindingStatus.ACTIVE
                    && (preserved == null || !preserved.id().equals(binding.id()))) {
                RuntimeBinding cancelledBinding = repository.save(
                        binding.withStatus(RuntimeBindingStatus.CANCELLED));
                cancelled.add(new AdmissionCancellation(binding, cancelledBinding));
            }
        }
        return List.copyOf(cancelled);
    }

    /**
     * 会话删除时永久取消 active 与 resumable binding，防止软删除会话仍持有下游 session 引用。
     */
    public void cancelAllForSession(String tenantId, String userId, String sessionId) {
        Map<String, RuntimeBinding> bindings = new LinkedHashMap<>();
        repository.findActiveBySession(tenantId, userId, sessionId)
                .forEach(binding -> bindings.put(binding.id(), binding));
        repository.findResumableBySession(tenantId, userId, sessionId, DEFAULT_RUNTIME_PROVIDER)
                .forEach(binding -> bindings.put(binding.id(), binding));
        bindings.values().forEach(binding -> save(binding.withStatus(RuntimeBindingStatus.CANCELLED)));
        cache.evict(tenantId, userId, sessionId);
    }

    public RuntimeBinding markNotRoutable(RuntimeBinding binding, String rejectCode) {
        if (binding == null) {
            return null;
        }
        return save(notRoutable(binding, rejectCode));
    }

    private RuntimeBinding notRoutable(RuntimeBinding binding, String rejectCode) {
        Map<String, Object> metadata = new LinkedHashMap<>(binding.metadata());
        if (rejectCode != null && !rejectCode.isBlank()) {
            metadata.put("lastRejectCode", rejectCode);
        }
        return binding.withMetadata(metadata).withStatus(RuntimeBindingStatus.CANCELLED);
    }

    /**
     * 补偿取消仍由指定 run 持有的 ACTIVE binding。
     *
     * <p>数据库条件更新失败表示绑定已变化，此时不得清理可能属于后续 run 的缓存。</p>
     */
    public boolean cancelActiveForRun(RuntimeBinding binding, String runId) {
        if (binding == null || runId == null || runId.isBlank()) {
            return false;
        }
        boolean cancelled = repository.cancelActiveForRun(binding.id(), runId);
        if (cancelled) {
            cache.evict(binding.tenantId(), binding.userId(), binding.chatSessionId());
        }
        return cancelled;
    }

    /**
     * Runtime 尚未订阅时，条件恢复本轮激活前的 Binding 快照。
     *
     * <p>恢复成功后按旧状态同步 Redis；条件不匹配表示 Binding 已由后续流程更新。</p>
     */
    public boolean restoreUnstartedForRun(RuntimeBinding previousBinding, String currentRunId) {
        if (previousBinding == null || currentRunId == null || currentRunId.isBlank()) {
            return false;
        }
        boolean restored = repository.restoreUnstartedForRun(previousBinding, currentRunId);
        if (restored) {
            synchronizeCache(previousBinding);
        }
        return restored;
    }

    /**
     * 直连或候选切换尚未订阅Runtime时，原子取消本轮新Binding并恢复admission取消的旧Binding。
     */
    @Transactional(timeoutString =
            "${financeex.domain-agent.binding-compensation-transaction-timeout-seconds:2}")
    public boolean restoreAdmissionBindingsForUnstartedRun(
            RuntimeBinding currentBinding,
            List<AdmissionCancellation> cancellations,
            String currentRunId) {
        if (currentBinding == null || currentRunId == null || currentRunId.isBlank()
                || cancellations == null || cancellations.isEmpty()) {
            return false;
        }
        if (!repository.cancelActiveForRun(currentBinding.id(), currentRunId)) {
            return false;
        }
        for (AdmissionCancellation cancellation : cancellations) {
            if (cancellation == null || cancellation.previous() == null || cancellation.cancelled() == null
                    || !repository.restoreCancelledAfterAdmission(
                            cancellation.previous(), cancellation.cancelled().updatedAt())) {
                throw new IllegalStateException("Admission binding compensation lost its expected snapshot");
            }
        }
        return true;
    }

    /**
     * 终态数据库事务提交后同步 Redis 热缓存。该方法不参与事务事实写入。
     */
    public void synchronizeCache(RuntimeBinding binding) {
        if (binding == null) {
            return;
        }
        if (binding.status().routable()) {
            cache.put(binding);
        } else {
            cache.evict(binding.tenantId(), binding.userId(), binding.chatSessionId());
        }
    }

    /**
     * 从 Runtime 事件中观察并保存 runtimeSessionId。
     *
     * @param binding 当前 Runtime 绑定。
     * @param event 本轮输出事件。
     * @return 更新后的 Runtime 绑定；未发生更新时返回原绑定。
     */
    public RuntimeBinding observeEvent(RuntimeBinding binding, ChatEvent event) {
        if (binding == null || event == null || event.payload() == null) {
            return binding;
        }
        Object runtimeSessionId = event.payload().get("runtimeSessionId");
        if (runtimeSessionId != null && !String.valueOf(runtimeSessionId).isBlank()) {
            String nextRuntimeSessionId = String.valueOf(runtimeSessionId);
            boolean sessionIdChanged = !nextRuntimeSessionId.equals(binding.runtimeSessionId());
            boolean establishRelaySession = DEFAULT_RUNTIME_PROVIDER.equals(binding.provider())
                    && (!relaySessionEstablished(binding) || binding.expiresAt() != null);
            if (!sessionIdChanged && !establishRelaySession) {
                return binding;
            }
            RuntimeBinding next = sessionIdChanged
                    ? binding.withRuntimeSessionId(nextRuntimeSessionId)
                    : binding;
            return save(markRelaySessionEstablished(next, nextRuntimeSessionId));
        }
        return binding;
    }

    private RuntimeBinding loadInteractionBinding(ChatInteractionRequest request) {
        String bindingId = request.runtimeBindingId();
        if (bindingId == null || bindingId.isBlank()) {
            throw new IllegalStateException("Interaction 请求缺少 RuntimeBinding: " + request.id());
        }
        return repository.findById(bindingId)
                .filter(binding -> request.tenantId().equals(binding.tenantId()))
                .filter(binding -> request.userId().equals(binding.userId()))
                .filter(binding -> request.sessionId().equals(binding.chatSessionId()))
                .orElseThrow(() -> new IllegalStateException("Interaction RuntimeBinding 不存在或归属不匹配: " + bindingId));
    }

    private void validateRelayInteractionBinding(ChatInteractionRequest request, RuntimeBinding binding) {
        if (!DEFAULT_RUNTIME_PROVIDER.equals(request.runtimeProvider())
                || !DEFAULT_RUNTIME_PROVIDER.equals(binding.provider())) {
            throw new IllegalStateException("Interaction RuntimeBinding 不是 Relay: " + binding.id());
        }
        if (binding.status() != RuntimeBindingStatus.ACTIVE) {
            throw new IllegalStateException("Interaction Relay Binding 不再是 ACTIVE: " + binding.id());
        }
        if (request.sourceRunId() == null || request.sourceRunId().isBlank()
                || !request.sourceRunId().equals(binding.lastRunId())) {
            throw new IllegalStateException("Interaction Relay Binding 已被其他 run 刷新: " + binding.id());
        }
        if (request.approvalId() == null || request.approvalId().isBlank()) {
            throw new IllegalStateException("Interaction 请求缺少 Relay approval_id: " + request.id());
        }
        if (request.runtimeSessionId() == null || request.runtimeSessionId().isBlank()
                || binding.runtimeSessionId() == null || binding.runtimeSessionId().isBlank()
                || !Objects.equals(request.runtimeSessionId(), binding.runtimeSessionId())) {
            throw new IllegalStateException("Interaction Relay runtimeSessionId 不可用或不匹配: " + binding.id());
        }
        if (!relaySessionEstablished(binding)) {
            throw new IllegalStateException("Interaction Relay session 尚未建立: " + binding.id());
        }
    }

    private RuntimeBinding withRuntimeSessionId(RuntimeBinding binding, String runtimeSessionId) {
        if (binding == null || runtimeSessionId == null || runtimeSessionId.isBlank()) {
            return binding;
        }
        RuntimeBinding next = runtimeSessionId.equals(binding.runtimeSessionId())
                ? binding
                : binding.withRuntimeSessionId(runtimeSessionId);
        return markRelaySessionEstablished(next, runtimeSessionId);
    }

    private String metadataText(RuntimeBinding binding, String key) {
        Object value = binding == null || binding.metadata() == null ? null : binding.metadata().get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private RuntimeBinding save(RuntimeBinding binding) {
        RuntimeBinding saved = repository.save(binding);
        synchronizeCache(saved);
        return saved;
    }

    private void cancelDuplicateBindings(List<RuntimeBinding> bindings, RuntimeBinding selected) {
        if (bindings == null || bindings.size() <= 1 || selected == null) {
            return;
        }
        for (RuntimeBinding binding : bindings) {
            if (!selected.id().equals(binding.id())) {
                save(binding.withStatus(RuntimeBindingStatus.CANCELLED));
            }
        }
        cache.put(selected);
    }

    private RuntimeBinding activateResumableForRun(RuntimeBinding binding, String runId, String leafMessageId) {
        RuntimeBinding next = markRelaySessionEstablished(binding, binding.runtimeSessionId())
                .withRun(runId, null);
        if (leafMessageId != null && !leafMessageId.isBlank()
                && !leafMessageId.equals(next.leafMessageId())) {
            next = next.withLeafMessageId(leafMessageId);
        }
        return save(next);
    }

    private RuntimeBinding markRelaySessionEstablished(RuntimeBinding binding, String runtimeSessionId) {
        if (binding == null || !DEFAULT_RUNTIME_PROVIDER.equals(binding.provider())) {
            return binding;
        }
        RuntimeBinding next = binding;
        if (runtimeSessionId != null && !runtimeSessionId.isBlank()
                && !runtimeSessionId.equals(next.runtimeSessionId())) {
            next = next.withRuntimeSessionId(runtimeSessionId);
        }
        Map<String, Object> metadata = new LinkedHashMap<>(next.metadata());
        metadata.put(RUNTIME_SESSION_ESTABLISHED, true);
        return next.withMetadata(metadata).withExpiresAt(null);
    }

    private boolean relaySessionEstablished(RuntimeBinding binding) {
        return binding != null
                && DEFAULT_RUNTIME_PROVIDER.equals(binding.provider())
                && (binding.status() == RuntimeBindingStatus.RESUMABLE
                || Boolean.TRUE.equals(binding.metadata().get(RUNTIME_SESSION_ESTABLISHED)));
    }

    private Instant expiresAt(String provider, boolean relaySessionEstablished) {
        return RuntimeBindingExpirationPolicy.expiresAt(ttl,
                DEFAULT_RUNTIME_PROVIDER.equals(normalizeProvider(provider)) && relaySessionEstablished);
    }

    private boolean resumableForCurrentProvider(RuntimeBinding binding) {
        return binding != null
                && binding.status() == RuntimeBindingStatus.RESUMABLE
                && runtimeProvider.equals(binding.provider())
                && binding.runtimeSessionId() != null
                && !binding.runtimeSessionId().isBlank();
    }

    private boolean routableForCurrentProvider(RuntimeBinding binding, Instant now) {
        return binding.routableAt(now) && runtimeProvider.equals(binding.provider());
    }

    /**
     * 读取 Relay Binding 的调用档案。存量 Binding 缺少档案时按 Delegate 兼容。
     */
    public RuntimeProfile runtimeProfile(RuntimeBinding binding) {
        if (binding == null || !runtimeProvider.equals(binding.provider())) {
            return RuntimeProfile.DELEGATE;
        }
        return bindingProfile(binding).profile();
    }

    /** 读取Relay专家Binding中已经固化的动态角色名。 */
    public String runtimeRoleName(RuntimeBinding binding) {
        if (binding == null || !runtimeProvider.equals(binding.provider())) {
            return null;
        }
        return bindingProfile(binding).roleName();
    }

    /** 是否为前端固定选择的Relay专家Binding。 */
    public boolean isPinnedDomainExpert(RuntimeBinding binding) {
        return binding != null
                && runtimeProvider.equals(binding.provider())
                && RuntimeProfileMetadata.isPinnedDomainExpert(binding.metadata());
    }

    /**
     * 将 Relay Binding 的调用档案转换为 ChatRun 私有 metadata。
     */
    public Map<String, Object> runProfileMetadata(RuntimeBinding binding) {
        if (binding == null || !runtimeProvider.equals(binding.provider())) {
            return Map.of();
        }
        return Map.of(RuntimeProfileMetadata.RUN_METADATA_KEY, bindingProfile(binding).toMetadata());
    }

    private RuntimeProfileMetadata.Snapshot configuredProfile(RuntimeProfile profile, String runtimeRoleName) {
        return RuntimeProfileMetadata.bindingSnapshot(
                RuntimeProfileMetadata.bindingMetadata(
                        profile, delegateAppMode, domainExpertAppMode, runtimeRoleName),
                delegateAppMode, domainExpertAppMode);
    }

    private RuntimeProfileMetadata.Snapshot bindingProfile(RuntimeBinding binding) {
        return RuntimeProfileMetadata.bindingSnapshot(
                binding == null ? Map.of() : binding.metadata(),
                delegateAppMode, domainExpertAppMode);
    }

    private boolean matchingProfile(RuntimeBinding binding, RuntimeProfileMetadata.Snapshot desiredProfile) {
        try {
            return desiredProfile.equals(bindingProfile(binding));
        } catch (IllegalStateException ex) {
            // 非法档案不能静默降级为 Delegate，否则可能把请求发到错误的 Relay 会话。
            return false;
        }
    }

    private void cancelActiveExceptPinnedDomainExpert(
            String tenantId, String userId, String sessionId, String roleName) {
        List<RuntimeBinding> active = repository.findActiveBySession(tenantId, userId, sessionId);
        if (active.isEmpty()) {
            active = repository.findActiveBySession(tenantId, userId, sessionId, runtimeProvider);
        }
        RuntimeBinding preserved = newestPinnedDomainExpert(active, roleName);
        for (RuntimeBinding binding : active) {
            if (binding.status() == RuntimeBindingStatus.ACTIVE
                    && (preserved == null || !preserved.id().equals(binding.id()))) {
                if (!cancelActiveForRun(binding, binding.lastRunId())) {
                    throw new ChatEventAppendRejectedException(
                            "Pinned domain expert Binding 条件取消失败: bindingId=" + binding.id());
                }
            }
        }
    }

    private RuntimeBinding newestPinnedDomainExpert(List<RuntimeBinding> bindings, String roleName) {
        if (bindings == null || roleName == null || roleName.isBlank()) {
            return null;
        }
        return bindings.stream()
                .filter(this::isPinnedDomainExpert)
                .filter(binding -> roleName.equals(runtimeRoleName(binding)))
                .max(Comparator.comparing(RuntimeBinding::updatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private Map<String, RuntimeBinding> activeBindingsForAdmission(
            String tenantId, String userId, String sessionId) {
        Map<String, RuntimeBinding> active = new LinkedHashMap<>();
        repository.findActiveBySession(tenantId, userId, sessionId)
                .forEach(binding -> active.putIfAbsent(binding.id(), binding));
        if (active.isEmpty()) {
            repository.findActiveBySession(tenantId, userId, sessionId, runtimeProvider)
                    .forEach(binding -> active.putIfAbsent(binding.id(), binding));
            repository.findActiveBySession(tenantId, userId, sessionId, DOMAIN_AGENT_PROVIDER)
                    .forEach(binding -> active.putIfAbsent(binding.id(), binding));
        }
        return active;
    }

    private RuntimeBinding newDomainAgentBinding(DomainAgentBindingCommand command) {
        Map<String, Object> metadata = domainAgentMetadata(
                command.domainAgentId(), command.routeSource(), command.intentMetadata(), null,
                command.agentMode());
        return newBinding(new RuntimeBindingCreateCommand(
                command.tenantId(), command.userId(), command.sessionId(), DOMAIN_AGENT_PROVIDER,
                command.runId(), command.leafMessageId(), command.sessionId(), metadata));
    }

    private void validateDomainAgentBindingCommand(DomainAgentBindingCommand command) {
        if (command == null || command.domainAgentId() == null || command.domainAgentId().isBlank()) {
            throw new IllegalArgumentException("DomainAgent ID 不能为空");
        }
    }

    private RuntimeBinding requireDeferredCandidate(DeferredDomainAgentBinding deferred) {
        if (deferred == null || deferred.candidate() == null) {
            throw new IllegalArgumentException("Deferred DomainAgent binding must not be null");
        }
        return deferred.candidate();
    }

    private List<AdmissionCancellation> cancelOtherActiveBindingsForDeferred(
            RuntimeBinding candidate) {
        Map<String, RuntimeBinding> active = activeBindingsForAdmission(
                candidate.tenantId(), candidate.userId(), candidate.chatSessionId());
        List<AdmissionCancellation> cancelled = new ArrayList<>();
        for (RuntimeBinding binding : active.values()) {
            if (binding.status() == RuntimeBindingStatus.ACTIVE
                    && !binding.id().equals(candidate.id())) {
                RuntimeBinding cancelledBinding = repository.save(
                        binding.withStatus(RuntimeBindingStatus.CANCELLED));
                cancelled.add(new AdmissionCancellation(binding, cancelledBinding));
            }
        }
        return List.copyOf(cancelled);
    }

    private void cancelOtherActiveBindingsForRouteSwitch(
            RuntimeBinding candidate,
            String sourceBindingId) {
        activeBindingsForAdmission(
                candidate.tenantId(), candidate.userId(), candidate.chatSessionId())
                .values()
                .stream()
                .filter(binding -> binding.status() == RuntimeBindingStatus.ACTIVE)
                .filter(binding -> !binding.id().equals(sourceBindingId))
                .filter(binding -> !binding.id().equals(candidate.id()))
                .forEach(binding -> repository.save(
                        binding.withStatus(RuntimeBindingStatus.CANCELLED)));
    }

    private void validateDomainAgentRouteSwitch(
            ChatInteractionRequest interaction,
            DomainAgentBindingCommand command,
            RunExecutionClaim claim) {
        validateDomainAgentBindingCommand(command);
        if (interaction == null || claim == null
                || !Objects.equals(command.runId(), claim.runId())
                || !Objects.equals(command.tenantId(), interaction.tenantId())
                || !Objects.equals(command.userId(), interaction.userId())
                || !Objects.equals(command.sessionId(), interaction.sessionId())
                || (interaction.continueRunId() != null
                && !interaction.continueRunId().isBlank()
                && !Objects.equals(command.runId(), interaction.continueRunId()))) {
            throw new IllegalArgumentException("DomainAgent route-switch Binding 参数不完整或不匹配");
        }
    }

    private void validateDomainAgentRouteSwitchSource(
            ChatInteractionRequest interaction,
            RuntimeBinding source) {
        if (!DOMAIN_AGENT_PROVIDER.equals(interaction.runtimeProvider())
                || !DOMAIN_AGENT_PROVIDER.equals(source.provider())
                || (source.status() != RuntimeBindingStatus.ACTIVE
                && source.status() != RuntimeBindingStatus.CANCELLED)
                || interaction.sourceRunId() == null
                || interaction.sourceRunId().isBlank()
                || !Objects.equals(interaction.sourceRunId(), source.lastRunId())) {
            throw new ChatEventAppendRejectedException(
                    "DomainAgent route-switch source Binding 已变化: bindingId=" + source.id());
        }
    }

    private RuntimeBinding routeSwitchCancelledSource(
            RuntimeBinding source,
            ChatInteractionRequest interaction,
            String runId) {
        RuntimeBinding next = withRuntimeSessionId(source, interaction.runtimeSessionId())
                .withRun(runId, expiresAt(source.provider(), false));
        if (interaction.assistantMessageId() != null
                && !interaction.assistantMessageId().isBlank()
                && !interaction.assistantMessageId().equals(next.leafMessageId())) {
            next = next.withLeafMessageId(interaction.assistantMessageId());
        }
        return notRoutable(next, interactionPayloadText(interaction, "refusalCode"));
    }

    private String interactionPayloadText(
            ChatInteractionRequest interaction,
            String key) {
        Object value = interaction == null || interaction.requestPayload() == null
                ? null
                : interaction.requestPayload().get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private RuntimeBinding saveDeferredCandidate(
            DeferredDomainAgentBinding deferred,
            RuntimeBinding candidate) {
        if (!deferred.reusesExistingBinding()) {
            if (repository.findById(candidate.id()).isPresent()) {
                throw new IllegalStateException("Deferred DomainAgent binding ID already exists");
            }
            return repository.save(candidate);
        }
        RuntimeBinding previous = deferred.previousBinding();
        RuntimeBinding current = repository.findById(previous.id())
                .filter(binding -> binding.status() == RuntimeBindingStatus.ACTIVE)
                .filter(binding -> previous.tenantId().equals(binding.tenantId()))
                .filter(binding -> previous.userId().equals(binding.userId()))
                .filter(binding -> previous.chatSessionId().equals(binding.chatSessionId()))
                .filter(binding -> DOMAIN_AGENT_PROVIDER.equals(binding.provider()))
                .filter(binding -> Objects.equals(previous.lastRunId(), binding.lastRunId()))
                .orElseThrow(() -> new ChatEventAppendRejectedException(
                        "Deferred DomainAgent binding is no longer current: bindingId=" + previous.id()));
        RuntimeBinding next = new RuntimeBinding(
                current.id(), current.tenantId(), current.userId(), current.chatSessionId(), current.provider(),
                candidate.leafMessageId(), current.runtimeSessionId(), RuntimeBindingStatus.ACTIVE,
                candidate.lastRunId(), candidate.expiresAt(), current.createdAt(), candidate.updatedAt(),
                candidate.metadata());
        return repository.save(next);
    }

    public record AdmissionCancellation(RuntimeBinding previous, RuntimeBinding cancelled) {
        public AdmissionCancellation {
            if (previous == null || cancelled == null || !previous.id().equals(cancelled.id())) {
                throw new IllegalArgumentException("Admission binding cancellation snapshot is invalid");
            }
        }
    }

    public record DeferredDomainAgentBindingActivation(
            RuntimeBinding binding,
            RuntimeBinding previousBinding,
            List<AdmissionCancellation> cancellations
    ) {
        public DeferredDomainAgentBindingActivation {
            cancellations = cancellations == null ? List.of() : List.copyOf(cancellations);
        }
    }

    private Map<String, Object> bindingMetadata(
            RuntimeBinding previous,
            RuntimeProfileMetadata.Snapshot desiredProfile,
            Map<String, Object> metadataOverlay) {
        if (previous != null && (metadataOverlay == null || metadataOverlay.isEmpty())) {
            return previous.metadata();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (previous != null && previous.metadata() != null) {
            metadata.putAll(previous.metadata());
        }
        if (metadataOverlay != null
                && Boolean.TRUE.equals(metadataOverlay.get(RuntimeProfileMetadata.RELAY_EXPERT_PINNED_KEY))) {
            // 本次没有 selectedIntent 时必须回退 roleName，不能沿用上一次选择的展示摘要。
            metadata.remove("intentCode");
            metadata.remove("intentName");
        }
        metadata.putAll(desiredProfile.toMetadata());
        if (metadataOverlay != null) {
            metadata.putAll(metadataOverlay);
        }
        return Map.copyOf(metadata);
    }

    private String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? DEFAULT_RUNTIME_PROVIDER : provider.trim();
    }

    private String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private Map<String, Object> domainAgentMetadata(String domainAgentId, String routeSource,
                                                    Map<String, Object> intentMetadata,
                                                    Map<String, Object> previousMetadata,
                                                    AgentModeProfile agentMode) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (previousMetadata != null) {
            metadata.putAll(previousMetadata);
        }
        metadata.put("domainAgentId", domainAgentId);
        metadata.put("routeSource", blankToDefault(routeSource, "intent"));
        if (intentMetadata != null && !intentMetadata.isEmpty()) {
            metadata.putAll(intentMetadata);
        }
        return AgentModeBindingContext.apply(metadata, agentMode);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
