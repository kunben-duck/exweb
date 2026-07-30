package com.huawei.it.ex.one.application.integration.runtime;

import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import java.util.List;
import java.util.Optional;

/**
 * RuntimeBinding 持久化仓储端口。
 *
 * <p>数据库是 RuntimeBinding 的事实源；Redis 只能作为热缓存，不能单独承载绑定状态。</p>
 */
public interface RuntimeBindingRepository {
    /**
     * 按主键查询 RuntimeBinding。
     *
     * @param bindingId RuntimeBinding 主键。
     * @return 绑定快照。
     */
    Optional<RuntimeBinding> findById(String bindingId);

    /**
     * 查询当前会话可续接的 Runtime 绑定。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @param provider 当前装配的 AgentRuntime provider 编码。
     * @return 当前可续接 Runtime 绑定。
     */
    Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId, String provider);

    /**
     * 按消息树 leaf 查询当前会话可续接的 Runtime 绑定。
     *
     * <p>leaf 为空表示根路径绑定；数据库仓储使用
     * {@code tenantId + userId + sessionId + provider + leafMessageId + ACTIVE} 精确查询。</p>
     */
    default Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId, String provider,
                                                String leafMessageId) {
        return leafMessageId == null || leafMessageId.isBlank()
                ? findActive(tenantId, userId, sessionId, provider)
                : Optional.empty();
    }

    /**
     * 查询某会话当前 provider 下所有 active Runtime 绑定。
     *
     * <p>消息树引入后，RuntimeBinding 会按 leaf 维度隔离。当前端显式开启新任务时，
     * 需要释放整条会话下所有可续接绑定，避免旧路径继续误用 Runtime session。</p>
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @param provider 当前装配的 AgentRuntime provider 编码。
     * @return 当前会话所有可续接 Runtime 绑定。
     */
    default List<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId, String provider) {
        return findActive(tenantId, userId, sessionId, provider, null).stream().toList();
    }

    /**
     * 查询某会话下所有 provider 的 active 绑定。
     */
    default List<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId) {
        return List.of();
    }

    /**
     * 查询会话下指定 provider 可由后续路由恢复、但不参与自动路由的绑定。
     */
    default List<RuntimeBinding> findResumableBySession(String tenantId, String userId, String sessionId,
                                                        String provider) {
        return List.of();
    }

    /**
     * 保存 Runtime 绑定。
     *
     * @param binding Runtime 绑定。
     * @return 已保存的 Runtime 绑定。
     */
    RuntimeBinding save(RuntimeBinding binding);

    /**
     * 仅当绑定仍由指定 run 持有且保持 ACTIVE 时取消绑定。
     *
     * <p>生产数据库实现必须使用单条条件更新，避免迟到补偿覆盖后续 run 已刷新的绑定。
     * 默认实现仅用于测试替身和兼容仓储。</p>
     *
     * @param bindingId RuntimeBinding 主键。
     * @param runId 创建或刷新该绑定的 run 标识。
     * @return true 表示本次条件取消成功。
     */
    default boolean cancelActiveForRun(String bindingId, String runId) {
        if (bindingId == null || bindingId.isBlank() || runId == null || runId.isBlank()) {
            return false;
        }
        return findById(bindingId)
                .filter(binding -> binding.status() == RuntimeBindingStatus.ACTIVE)
                .filter(binding -> runId.equals(binding.lastRunId()))
                .map(binding -> {
                    save(binding.withStatus(RuntimeBindingStatus.CANCELLED));
                    return true;
                })
                .orElse(false);
    }
}
