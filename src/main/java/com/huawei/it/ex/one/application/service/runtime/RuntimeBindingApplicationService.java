package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.application.integration.agent.AgentModeBindingContext;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

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
                                            IdGenerator idGenerator,
                                            @Value("${financeex.runtime-binding.ttl:0s}") Duration ttl,
                                            @Value("${financeex.agent-runtime.default-provider:relay}") String runtimeProvider) {
        this.repository = repository;
        this.cache = cache;
        this.idGenerator = idGenerator;
        this.ttl = RuntimeBindingExpirationPolicy.normalize(ttl);
        this.runtimeProvider = normalizeProvider(runtimeProvider);
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
        Instant now = Instant.now();
        List<RuntimeBinding> activeBindings = repository.findActiveBySession(tenantId, userId, sessionId, runtimeProvider)
                .stream()
                .filter(binding -> routableForCurrentProvider(binding, now))
                .sorted(Comparator.comparing(RuntimeBinding::updatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .toList();
        if (!activeBindings.isEmpty()) {
            RuntimeBinding selected = touchForRun(activeBindings.getFirst(), runId);
            cancelDuplicateBindings(activeBindings, selected);
            cache.put(selected);
            return new RuntimeBindingResolution(selected, RuntimeSessionMode.RESUME);
        }
        List<RuntimeBinding> resumableBindings = repository
                .findResumableBySession(tenantId, userId, sessionId, runtimeProvider)
                .stream()
                .filter(this::resumableForCurrentProvider)
                .sorted(Comparator.comparing(RuntimeBinding::updatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .toList();
        if (!resumableBindings.isEmpty()) {
            RuntimeBinding selected = activateResumableForRun(
                    resumableBindings.getFirst(), runId, leafMessageId);
            cancelDuplicateBindings(resumableBindings, selected);
            return new RuntimeBindingResolution(selected, RuntimeSessionMode.RESUME);
        }
        RuntimeBinding created = create(tenantId, userId, sessionId, runId, leafMessageId);
        return new RuntimeBindingResolution(created, RuntimeSessionMode.NEW);
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
        Instant now = Instant.now();
        String id = idGenerator.newId("runtime_binding",
                IdGenerateContext.of(command.tenantId(), command.userId(), command.sessionId()));
        RuntimeBinding binding = new RuntimeBinding(id, command.tenantId(), command.userId(), command.sessionId(),
                normalizeProvider(command.provider()), command.leafMessageId(),
                blankToDefault(command.runtimeSessionId(), command.sessionId()), RuntimeBindingStatus.ACTIVE,
                command.runId(), expiresAt(command.provider(), false), now, now, command.metadata());
        return save(binding);
    }

    public RuntimeBinding create(String tenantId, String userId, String sessionId, String runId) {
        return create(tenantId, userId, sessionId, runId, null);
    }

    public RuntimeBinding bindDomainAgentForRun(DomainAgentBindingCommand command) {
        if (command == null || command.domainAgentId() == null || command.domainAgentId().isBlank()) {
            throw new IllegalArgumentException("DomainAgent ID 不能为空");
        }
        cancelActive(command.tenantId(), command.userId(), command.sessionId());
        Map<String, Object> metadata = domainAgentMetadata(command.domainAgentId(), command.routeSource(),
                command.intentMetadata(), null, command.agentMode());
        return create(new RuntimeBindingCreateCommand(command.tenantId(), command.userId(), command.sessionId(),
                DOMAIN_AGENT_PROVIDER, command.runId(), command.leafMessageId(), command.sessionId(), metadata));
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
        if (DOMAIN_AGENT_PROVIDER.equals(binding.provider())) {
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
        Map<String, RuntimeBinding> active = new LinkedHashMap<>();
        repository.findActiveBySession(tenantId, userId, sessionId)
                .forEach(binding -> active.putIfAbsent(binding.id(), binding));
        if (active.isEmpty()) {
            repository.findActiveBySession(tenantId, userId, sessionId, DEFAULT_RUNTIME_PROVIDER)
                    .forEach(binding -> active.putIfAbsent(binding.id(), binding));
            repository.findActiveBySession(tenantId, userId, sessionId, DOMAIN_AGENT_PROVIDER)
                    .forEach(binding -> active.putIfAbsent(binding.id(), binding));
        }
        List<RuntimeBinding> cancelled = new ArrayList<>();
        for (RuntimeBinding binding : active.values()) {
            if (binding.status() == RuntimeBindingStatus.ACTIVE) {
                cancelled.add(repository.save(binding.withStatus(RuntimeBindingStatus.CANCELLED)));
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
        Map<String, Object> metadata = new LinkedHashMap<>(binding.metadata());
        if (rejectCode != null && !rejectCode.isBlank()) {
            metadata.put("lastRejectCode", rejectCode);
        }
        return save(binding.withMetadata(metadata).withStatus(RuntimeBindingStatus.CANCELLED));
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

    private String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? DEFAULT_RUNTIME_PROVIDER : provider.trim();
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
