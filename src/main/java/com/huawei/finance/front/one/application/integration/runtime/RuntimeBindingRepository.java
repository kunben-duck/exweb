package com.huawei.finance.front.one.application.integration.runtime;

import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
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
     * 保存 Runtime 绑定。
     *
     * @param binding Runtime 绑定。
     * @return 已保存的 Runtime 绑定。
     */
    RuntimeBinding save(RuntimeBinding binding);
}
