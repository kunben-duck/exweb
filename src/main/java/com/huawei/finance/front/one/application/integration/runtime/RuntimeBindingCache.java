package com.huawei.finance.front.one.application.integration.runtime;

import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import java.util.Optional;

/**
 * RuntimeBinding Redis 热缓存端口。
 */
public interface RuntimeBindingCache {
    /**
     * 从热缓存读取 Runtime 绑定。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return 缓存中的 Runtime 绑定。
     */
    Optional<RuntimeBinding> get(String tenantId, String userId, String sessionId);

    /**
     * 写入或刷新 Runtime 绑定热缓存。
     *
     * @param binding Runtime 绑定。
     */
    void put(RuntimeBinding binding);

    /**
     * 删除当前会话的 Runtime 绑定热缓存。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     */
    void evict(String tenantId, String userId, String sessionId);
}
