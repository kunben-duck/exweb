package com.huawei.it.ex.one.application.integration.runtime;

import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
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
     * 在 run/execution 写入权保护下刷新等待态 Relay Binding。
     *
     * <p>生产数据库实现必须在同一短事务内锁定并校验 run/execution，再条件更新仍由
     * {@code expectedLastRunId} 持有的 ACTIVE binding。</p>
     */
    default Optional<RuntimeBinding> resumeInteractionWithExecutionGuard(
            RuntimeBinding binding,
            String expectedLastRunId,
            RunExecutionClaim claim) {
        if (binding == null || expectedLastRunId == null || expectedLastRunId.isBlank()
                || claim == null || !claim.runId().equals(binding.lastRunId())) {
            return Optional.empty();
        }
        return findById(binding.id())
                .filter(current -> current.status() == RuntimeBindingStatus.ACTIVE)
                .filter(current -> expectedLastRunId.equals(current.lastRunId()))
                .filter(current -> binding.provider().equals(current.provider()))
                .map(ignored -> save(binding));
    }

    /**
     * run-B 尚未订阅 Runtime 时，把 Binding 的最近 run 条件恢复为等待态来源 run。
     */
    default boolean restoreInteractionResume(String bindingId, String continueRunId, String sourceRunId) {
        if (bindingId == null || bindingId.isBlank() || continueRunId == null || continueRunId.isBlank()
                || sourceRunId == null || sourceRunId.isBlank()) {
            return false;
        }
        return findById(bindingId)
                .filter(binding -> binding.status() == RuntimeBindingStatus.ACTIVE)
                .filter(binding -> continueRunId.equals(binding.lastRunId()))
                .map(binding -> {
                    save(binding.withRun(sourceRunId, binding.expiresAt()));
                    return true;
                })
                .orElse(false);
    }

    /**
     * Runtime 尚未订阅时，将本轮激活的 Binding 条件恢复为激活前快照。
     *
     * <p>生产数据库实现必须同时匹配 Binding 归属、ACTIVE 状态和当前 run，避免迟到补偿
     * 覆盖后续 run 已经刷新过的 Binding。</p>
     *
     * @param previousBinding 激活前的完整 Binding 快照。
     * @param currentRunId 尚未启动 Runtime 的当前 run 标识。
     * @return true 表示恢复成功。
     */
    default boolean restoreUnstartedForRun(RuntimeBinding previousBinding, String currentRunId) {
        if (previousBinding == null || currentRunId == null || currentRunId.isBlank()) {
            return false;
        }
        return findById(previousBinding.id())
                .filter(current -> current.status() == RuntimeBindingStatus.ACTIVE)
                .filter(current -> currentRunId.equals(current.lastRunId()))
                .filter(current -> previousBinding.tenantId().equals(current.tenantId()))
                .filter(current -> previousBinding.userId().equals(current.userId()))
                .filter(current -> previousBinding.chatSessionId().equals(current.chatSessionId()))
                .filter(current -> previousBinding.provider().equals(current.provider()))
                .map(current -> {
                    save(previousBinding);
                    return true;
                })
                .orElse(false);
    }

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

    /**
     * 条件取消指定 Interaction 引用、且仍由来源或 continuation run 持有的 ACTIVE binding。
     *
     * <p>完整归属和 lastRunId 条件用于防止旧等待态 stop 误取消后续 run 已经刷新的 binding；
     * RESUMABLE binding 不在该更新范围内。</p>
     */
    default boolean cancelActiveForInteraction(
            RuntimeBinding binding,
            String sourceRunId,
            String continueRunId) {
        if (binding == null || binding.id() == null || binding.id().isBlank()
                || binding.tenantId() == null || binding.tenantId().isBlank()
                || binding.userId() == null || binding.userId().isBlank()
                || binding.chatSessionId() == null || binding.chatSessionId().isBlank()
                || sourceRunId == null || sourceRunId.isBlank()) {
            return false;
        }
        return findById(binding.id())
                .filter(current -> current.status() == RuntimeBindingStatus.ACTIVE)
                .filter(current -> binding.tenantId().equals(current.tenantId()))
                .filter(current -> binding.userId().equals(current.userId()))
                .filter(current -> binding.chatSessionId().equals(current.chatSessionId()))
                .filter(current -> sourceRunId.equals(current.lastRunId())
                        || (continueRunId != null && !continueRunId.isBlank()
                        && continueRunId.equals(current.lastRunId())))
                .map(current -> {
                    save(current.withStatus(RuntimeBindingStatus.CANCELLED));
                    return true;
                })
                .orElse(false);
    }
}
