package com.huawei.finance.front.one.application.service.runtime;

import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import com.huawei.finance.front.one.domain.runtime.RuntimeBindingStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AgentRuntime 多轮绑定应用服务。
 *
 * <p>简单 SubAgent 不创建任何绑定；只有进入当前 AgentRuntime provider 的复杂任务才会在这里建立
 * 会话续接索引。状态变更先写数据库，再刷新或删除 Redis 热缓存。</p>
 */
@Service
public class RuntimeBindingApplicationService {
    /** 当前上线版本默认 AgentRuntime provider 编码。 */
    public static final String DEFAULT_RUNTIME_PROVIDER = "relay";

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
     * @param runtimeProvider 当前装配的 AgentRuntime provider 编码。
     */
    public RuntimeBindingApplicationService(RuntimeBindingRepository repository, RuntimeBindingCache cache,
                                            IdGenerator idGenerator,
                                            @Value("${financeex.runtime-binding.ttl:3d}") Duration ttl,
                                            @Value("${financeex.agent-runtime.provider:relay}") String runtimeProvider) {
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
     * <p>Relay WebSocket 要求同一个 ChatService 会话只 {@code new} 一次，因此这里按会话维度复用
     * active binding，不再因消息树 leaf 切换而创建新的下游 Runtime session。leaf 仍会保存在 binding
     * 中用于诊断和后续消息树定位。</p>
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
     * <p>Relay WebSocket 会话只允许首次进入时 {@code new}，因此普通继续提问优先复用会话下最新的
     * active binding。该方法不会创建新绑定。</p>
     */
    public Optional<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId) {
        Instant now = Instant.now();
        List<RuntimeBinding> activeBindings = repository.findActiveBySession(tenantId, userId, sessionId, runtimeProvider)
                .stream()
                .filter(binding -> routableForCurrentProvider(binding, now))
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
        Instant now = Instant.now();
        String id = idGenerator.newId("runtime_binding", IdGenerateContext.of(tenantId, userId, sessionId));
        String runtimeSessionId = idGenerator.newId("runtime_session", IdGenerateContext.of(tenantId, userId, sessionId));
        RuntimeBinding binding = new RuntimeBinding(id, tenantId, userId, sessionId, runtimeProvider,
                leafMessageId, runtimeSessionId, RuntimeBindingStatus.ACTIVE, runId, expiresAt(), now, now, Map.of());
        return save(binding);
    }

    public RuntimeBinding create(String tenantId, String userId, String sessionId, String runId) {
        return create(tenantId, userId, sessionId, runId, null);
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
        repository.findActiveBySession(tenantId, userId, sessionId, runtimeProvider)
                .forEach(binding -> save(binding.withStatus(RuntimeBindingStatus.CANCELLED)));
        cache.evict(tenantId, userId, sessionId);
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
}
