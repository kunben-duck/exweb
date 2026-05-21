package com.huawei.finance.front.one.application.integration.conversation;

import java.time.Duration;

/**
 * stale run 恢复抢占的 Redis 优化锁端口。
 *
 * <p>该锁只用于减少多个实例同时打 openGauss 的竞争，不作为正确性事实源。
 * Redis 不可用或未抢到锁时，上层可以选择跳过本轮；真正的恢复权仍由 openGauss 条件更新裁决。</p>
 */
public interface ChatRunRecoverLock {
    /**
     * 尝试获取某个 run 的恢复优化锁。
     *
     * @param runId run 标识。
     * @param ownerInstanceId 当前实例 ID。
     * @param ttl 锁过期时间。
     * @return true 表示拿到锁或锁功能不可用时允许继续；false 表示本轮跳过。
     */
    boolean tryLock(String runId, String ownerInstanceId, Duration ttl);
}
