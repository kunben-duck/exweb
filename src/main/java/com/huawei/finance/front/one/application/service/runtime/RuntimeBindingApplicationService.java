package com.huawei.finance.front.one.application.service.runtime;

import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatInteractionRequest;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import com.huawei.finance.front.one.domain.runtime.RuntimeBindingStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
                                            @Value("${financeex.runtime-binding.ttl:3d}") Duration ttl,
                                            @Value("${financeex.agent-runtime.default-provider:relay}") String runtimeProvider) {
        this.repository = repository;
        this.cache = cache;
        this.idGenerator = idGenerator;
        this.ttl = ttl == null ? Duration.ofDays(3) : ttl;
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
     * session。Relay 在正常 run.completed 后会取消绑定，因此这里通常只会续接 DomainAgent 或
     * 未闭合的 Relay 等待态。</p>
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
     * 为复杂任务创建新的 AgentRuntime 绑定。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @param runId 本轮运行标识。
     * @return 已保存的 Runtime 绑定。
     */
    public RuntimeBinding create(String tenantId, String userId, String sessionId, String runId, String leafMessageId) {
        return create(new RuntimeBindingCreateCommand(tenantId, userId, sessionId, runtimeProvider,
                runId, leafMessageId, sessionId, Map.of()));
    }

    private RuntimeBinding create(RuntimeBindingCreateCommand command) {
        Instant now = Instant.now();
        String id = idGenerator.newId("runtime_binding",
                IdGenerateContext.of(command.tenantId(), command.userId(), command.sessionId()));
        RuntimeBinding binding = new RuntimeBinding(id, command.tenantId(), command.userId(), command.sessionId(),
                normalizeProvider(command.provider()), command.leafMessageId(),
                blankToDefault(command.runtimeSessionId(), command.sessionId()), RuntimeBindingStatus.ACTIVE,
                command.runId(), expiresAt(), now, now, command.metadata());
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
                command.intentMetadata(), null);
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
                binding.metadata());
        return save(binding.withMetadata(metadata).withRun(runId, expiresAt()));
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
        return save(binding.withRun(runId, expiresAt()));
    }

    /**
     * Interaction 续接必须回到创建等待态时的真实 RuntimeBinding。
     *
     * <p>这里不创建临时 binding，避免产生不会过期的 active 绑定；同时刷新本轮 runId、TTL、
     * leaf 和 runtimeSessionId，让续接完成后的下一轮提问仍能命中同一个 Relay 会话。</p>
     */
    public RuntimeBinding resumeForInteraction(ChatInteractionRequest request, String runId) {
        if (request == null) {
            throw new IllegalArgumentException("Interaction 请求不能为空");
        }
        RuntimeBinding binding = loadInteractionBinding(request);
        RuntimeBinding next = withRuntimeSessionId(binding, request.runtimeSessionId());
        return touchAndMoveToLeaf(next, runId, request.assistantMessageId());
    }

    /**
     * 刷新绑定活跃窗口，并移动到给定 leaf。
     *
     * <p>与 {@link #moveToLeaf(RuntimeBinding, String)} 不同，本方法即使 leaf 没变也会保存，
     * 因为 Interaction 续接完成时需要刷新 TTL 和 lastRunId。</p>
     */
    public RuntimeBinding touchAndMoveToLeaf(RuntimeBinding binding, String runId, String leafMessageId) {
        if (binding == null) {
            return null;
        }
        RuntimeBinding next = binding.withRun(runId, expiresAt());
        if (leafMessageId != null && !leafMessageId.isBlank()
                && !leafMessageId.equals(next.leafMessageId())) {
            next = next.withLeafMessageId(leafMessageId);
        }
        return save(next);
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
            if (nextRuntimeSessionId.equals(binding.runtimeSessionId())) {
                return binding;
            }
            return save(binding.withRuntimeSessionId(nextRuntimeSessionId));
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
        if (binding == null || runtimeSessionId == null || runtimeSessionId.isBlank()
                || runtimeSessionId.equals(binding.runtimeSessionId())) {
            return binding;
        }
        return binding.withRuntimeSessionId(runtimeSessionId);
    }

    private RuntimeBinding save(RuntimeBinding binding) {
        RuntimeBinding saved = repository.save(binding);
        if (!saved.status().routable()) {
            cache.evict(saved.tenantId(), saved.userId(), saved.chatSessionId());
        } else {
            cache.put(saved);
        }
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

    private Instant expiresAt() {
        return Instant.now().plus(ttl);
    }

    private boolean routableForCurrentProvider(RuntimeBinding binding, Instant now) {
        return binding.routableAt(now) && runtimeProvider.equals(binding.provider());
    }

    private String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? DEFAULT_RUNTIME_PROVIDER : provider.trim();
    }

    private Map<String, Object> domainAgentMetadata(String domainAgentId, String routeSource,
                                                    Map<String, Object> intentMetadata,
                                                    Map<String, Object> previousMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (previousMetadata != null) {
            metadata.putAll(previousMetadata);
        }
        metadata.put("domainAgentId", domainAgentId);
        metadata.put("routeSource", blankToDefault(routeSource, "intent"));
        if (intentMetadata != null && !intentMetadata.isEmpty()) {
            metadata.putAll(intentMetadata);
        }
        return Map.copyOf(metadata);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
