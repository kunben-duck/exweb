package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.integration.agent.binding.AgentBindingCache;
import com.huawei.finance.front.one.application.integration.agent.binding.AgentBindingRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.domain.agent.AgentBinding;
import com.huawei.finance.front.one.domain.agent.AgentBindingStatus;
import com.huawei.finance.front.one.domain.agent.AgentBindingType;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentBindingApplicationService {
    private final AgentBindingRepository repository;
    private final AgentBindingCache cache;
    private final IdGenerator idGenerator;
    private final Duration ttl;

    public AgentBindingApplicationService(AgentBindingRepository repository, AgentBindingCache cache, IdGenerator idGenerator,
                                          @Value("${financeex.agent-binding.ttl:3d}") Duration ttl) {
        this.repository = repository;
        this.cache = cache;
        this.idGenerator = idGenerator;
        this.ttl = ttl;
    }

    public Optional<AgentBinding> findActive(String tenantId, String userId, String sessionId) {
        Instant now = Instant.now();
        // Redis 是 active binding 的热路径。命中且未过期时，主链路会继续加载 TaskCard 或 Runtime 续接信息。
        Optional<AgentBinding> cached = cache.get(tenantId, userId, sessionId).filter(binding -> binding.routableAt(now));
        if (cached.isPresent()) {
            return cached;
        }

        // Redis miss 或热缓存不可用时回源 openGauss。openGauss 是 binding 的事实源，
        // 回源成功后立即回填 Redis，让后续多轮消息恢复到低延迟路径。
        Optional<AgentBinding> persisted = repository.findActive(tenantId, userId, sessionId)
                .filter(binding -> binding.routableAt(now));
        persisted.ifPresent(cache::put);
        return persisted;
    }

    public AgentBinding createSubAgentBinding(String tenantId, String userId, String sessionId, String runId, String agentCode) {
        return save(newBinding(tenantId, userId, sessionId, runId, AgentBindingType.SUB_AGENT, agentCode, null));
    }

    public AgentBinding createRuntimeBinding(String tenantId, String userId, String sessionId, String runId, String provider) {
        return save(newBinding(tenantId, userId, sessionId, runId, AgentBindingType.AGENT_RUNTIME, null, provider));
    }

    public AgentBinding touchForRun(AgentBinding binding, String runId) {
        // 每轮续接都刷新 runId 和过期时间，表示这个任务仍处于用户会话活跃窗口内。
        return save(binding.withRun(runId, expiresAt()));
    }

    public AgentBinding updateStatus(AgentBinding binding, AgentBindingStatus status) {
        if (binding == null || status == null) {
            return binding;
        }
        return save(binding.withStatus(status));
    }

    public void cancelActive(String tenantId, String userId, String sessionId) {
        // 显式取消要同时写事实源和删除热缓存。即使 openGauss 写入失败，也要清 Redis，
        // 避免当前服务继续把后续输入路由到旧 Agent。
        findActive(tenantId, userId, sessionId).ifPresent(binding -> save(binding.withStatus(AgentBindingStatus.CANCELLED)));
        cache.evict(tenantId, userId, sessionId);
    }

    public boolean observeEvent(AgentBinding binding, ChatEvent event) {
        if (binding == null || event == null || event.payload() == null) {
            return false;
        }
        Object taskStatus = event.payload().get("taskStatus");
        AgentBinding nextBinding = binding;
        // 下游可以在任意事件 payload 中回传自己的会话标识。SuperAgent 不理解其内部语义，
        // 只负责保存并在下一轮 query 时原样带回。
        Object agentSessionId = event.payload().get("agentSessionId");
        if (agentSessionId != null && !String.valueOf(agentSessionId).isBlank()) {
            nextBinding = nextBinding.withAgentSessionId(String.valueOf(agentSessionId));
        }
        Object runtimeSessionId = event.payload().get("runtimeSessionId");
        if (runtimeSessionId != null && !String.valueOf(runtimeSessionId).isBlank()) {
            nextBinding = nextBinding.withRuntimeSessionId(String.valueOf(runtimeSessionId));
        }
        if (taskStatus == null) {
            if (nextBinding != binding) {
                save(nextBinding);
            }
            return false;
        }
        // taskStatus 是下游控制多轮保持的信号：
        // REQUIRES_USER_INPUT/ACTIVE 继续保持，COMPLETED/FAILED/CANCELLED 等终态释放热缓存。
        AgentBindingStatus next = AgentBindingStatus.fromTaskStatus(String.valueOf(taskStatus), nextBinding.status());
        save(nextBinding.withStatus(next));
        return true;
    }

    public void completeIfNoTerminalStatus(AgentBinding binding) {
        if (binding == null) {
            return;
        }
        findActive(binding.tenantId(), binding.userId(), binding.chatSessionId()).ifPresent(active -> {
            if (active.status().terminal()) {
                return;
            }
            // 没有 taskStatus 的场景需要按 Agent 类型兜底：
            // - SubAgent 通常承接简单确定性任务，默认视为完成。
            // - AgentRuntime 负责复杂规划，默认保持 active，避免中途追问被打断。
            AgentBindingStatus fallback = active.bindingType() == AgentBindingType.AGENT_RUNTIME
                    ? AgentBindingStatus.ACTIVE
                    : AgentBindingStatus.COMPLETED;
            save(active.withStatus(fallback));
        });
    }

    public AgentBinding updateAgentSession(AgentBinding binding, String agentSessionId) {
        if (binding == null || agentSessionId == null || agentSessionId.isBlank()) {
            return binding;
        }
        return save(binding.withAgentSessionId(agentSessionId));
    }

    public AgentBinding updateRuntimeSession(AgentBinding binding, String runtimeSessionId) {
        if (binding == null || runtimeSessionId == null || runtimeSessionId.isBlank()) {
            return binding;
        }
        return save(binding.withRuntimeSessionId(runtimeSessionId));
    }

    private AgentBinding newBinding(String tenantId, String userId, String sessionId, String runId,
                                    AgentBindingType bindingType, String agentCode, String provider) {
        Instant now = Instant.now();
        String id = idGenerator.newId("binding", IdGenerateContext.of(tenantId, userId, sessionId));
        return new AgentBinding(id, tenantId, userId, sessionId, bindingType, agentCode, provider, null, null,
                AgentBindingStatus.ACTIVE, runId, expiresAt(), now, now, Map.of());
    }

    private AgentBinding save(AgentBinding binding) {
        // 先写 openGauss，并且必须写成功。Redis 只是热缓存，不能在事实源失败时单独承载状态。
        AgentBinding saved = repository.save(binding);
        // 终态不再参与续接，必须清除 Redis；非终态则刷新热缓存和 TTL。
        if (!saved.status().routable()) {
            cache.evict(saved.tenantId(), saved.userId(), saved.chatSessionId());
        } else {
            cache.put(saved);
        }
        return saved;
    }

    private Instant expiresAt() {
        return Instant.now().plus(ttl == null ? Duration.ofDays(3) : ttl);
    }
}
