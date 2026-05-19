package com.huawei.finance.front.one.application.integration.runtime;

import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import java.util.List;
import java.util.Optional;

/**
 * RuntimeBinding 持久化仓储端口。
 *
 * <p>openGauss 是 RuntimeBinding 的事实源；Redis 只能作为热缓存，不能单独承载绑定状态。</p>
 */
public interface RuntimeBindingRepository {
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
     * <p>默认实现只兼容无 leaf 的旧测试与迁移期实现；生产 openGauss 仓储会覆盖该方法，
     * 使用 {@code tenantId + userId + sessionId + provider + leafMessageId + ACTIVE} 精确查询。</p>
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
     * 保存 Runtime 绑定。
     *
     * @param binding Runtime 绑定。
     * @return 已保存的 Runtime 绑定。
     */
    RuntimeBinding save(RuntimeBinding binding);
}
