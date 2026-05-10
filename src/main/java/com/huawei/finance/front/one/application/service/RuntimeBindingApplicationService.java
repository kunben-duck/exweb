package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import com.huawei.finance.front.one.domain.runtime.RuntimeBindingStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Relay Runtime 多轮绑定应用服务。
 *
 * <p>简单 SubAgent 不创建任何绑定；只有进入 Relay Runtime 的复杂任务才会在这里建立会话续接索引。
 * 状态变更先写 openGauss，再刷新或删除 Redis 热缓存。</p>
 */
@Service
public class RuntimeBindingApplicationService {
    /** 当前正式版本唯一 Runtime provider 编码。 */
    public static final String RELAY_AGENT_PROVIDER = "relay-agent";

    private final RuntimeBindingRepository repository;
    private final RuntimeBindingCache cache;
    private final IdGenerator idGenerator;
    private final Duration ttl;

    /**
     * 创建 Runtime 绑定服务。
     *
     * @param repository RuntimeBinding 事实源仓储。
     * @param cache RuntimeBinding Redis 热缓存。
     * @param idGenerator 统一 ID 生成器。
     * @param ttl Runtime 绑定可续接窗口。
     */
    public RuntimeBindingApplicationService(RuntimeBindingRepository repository, RuntimeBindingCache cache,
                                            IdGenerator idGenerator,
                                            @Value("${financeex.runtime-binding.ttl:3d}") Duration ttl) {
        this.repository = repository;
        this.cache = cache;
        this.idGenerator = idGenerator;
        this.ttl = ttl == null ? Duration.ofDays(3) : ttl;
    }

    /**
     * 查询当前会话 active RuntimeBinding，优先 Redis，miss 后回源 openGauss 并回填。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return 当前可续接 Runtime 绑定。
     */
    public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId) {
        Instant now = Instant.now();
        Optional<RuntimeBinding> cached = cache.get(tenantId, userId, sessionId);
        if (cached.isPresent()) {
            if (cached.get().routableAt(now)) {
                return cached;
            }
            // Redis 是热缓存，不是事实源；发现过期或不可路由的缓存后立即清理，避免后续请求反复读到脏索引。
            cache.evict(tenantId, userId, sessionId);
        }
        Optional<RuntimeBinding> persisted = repository.findActive(tenantId, userId, sessionId)
                .filter(binding -> binding.routableAt(now));
        persisted.ifPresent(cache::put);
        return persisted;
    }

    /**
     * 为复杂任务创建新的 Relay Runtime 绑定。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @param runId 本轮运行标识。
     * @return 已保存的 Runtime 绑定。
     */
    public RuntimeBinding create(String tenantId, String userId, String sessionId, String runId) {
        Instant now = Instant.now();
        String id = idGenerator.newId("runtime_binding", IdGenerateContext.of(tenantId, userId, sessionId));
        RuntimeBinding binding = new RuntimeBinding(id, tenantId, userId, sessionId, RELAY_AGENT_PROVIDER,
                null, RuntimeBindingStatus.ACTIVE, runId, expiresAt(), now, now, Map.of());
        return save(binding);
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
     * 取消当前 active Runtime 绑定。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     */
    public void cancelActive(String tenantId, String userId, String sessionId) {
        findActive(tenantId, userId, sessionId)
                .ifPresent(binding -> save(binding.withStatus(RuntimeBindingStatus.CANCELLED)));
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

    private Instant expiresAt() {
        return Instant.now().plus(ttl);
    }
}
