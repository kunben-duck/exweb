package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunStatus;
import com.huawei.finance.front.one.domain.chat.RunExecutionClaim;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ChatRun 事实源仓储端口。
 *
 * <p>数据库是 run 生命周期状态的最终事实源；Redis 只能保存 active run 和取消标记。</p>
 */
public interface ChatRunRepository {
    /**
     * 保存 run 快照。
     *
     * @param run run 生命周期快照。
     * @return 已保存的 run。
     */
    ChatRun save(ChatRun run);

    /**
     * 严格插入一个新 run，不执行 upsert。
     *
     * <p>生产实现依赖 active-run 部分唯一索引完成跨实例并发准入。</p>
     */
    default ChatRun insert(ChatRun run) {
        return save(run);
    }

    /**
     * 仅当指定 Interaction 仍持有当前 continueRunId 时创建 continuation run。
     */
    default Optional<ChatRun> insertInteractionContinuationIfClaimed(ChatRun run, String interactionId) {
        return Optional.of(save(run));
    }

    /**
     * 按 runId 查询 run。
     *
     * @param runId run 标识。
     * @return run 快照；不存在时为空。
     */
    Optional<ChatRun> findById(String runId);

    /**
     * 按用户归属查询 run，用于 stop 权限校验。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param runId run 标识。
     * @return 当前用户拥有的 run；不存在或不属于当前用户时为空。
     */
    Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId);

    /**
     * 按用户归属批量查询 run，用于历史消息装配等只读场景，避免逐条查询。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param runIds run 标识集合。
     * @return 当前用户拥有的 run 快照列表；不存在或不属于当前用户的 run 不返回。
     */
    default List<ChatRun> findByTenantIdAndUserIdAndIds(String tenantId, String userId, Collection<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        return runIds.stream()
                .filter(runId -> runId != null && !runId.isBlank())
                .distinct()
                .map(runId -> findByTenantIdAndUserIdAndId(tenantId, userId, runId))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * 查询会话当前仍在运行或取消中的 run。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return 当前 active run；不存在时为空。
     */
    Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId);

    /**
     * 查询超过初始化宽限期、尚未创建 execution 的普通 run。
     *
     * <p>Interaction continuation 由专用对账链路处理，生产实现应排除仍处于
     * RESPONDING 的 Interaction claim。</p>
     */
    default List<ChatRun> findExecutionInitOrphans(Instant orphanBefore, int limit) {
        return List.of();
    }

    /**
     * 在 owner 提交 run 终态前，通过单条条件 UPDATE 获取当前 run 行的事务级写入栅栏。
     *
     * <p>生产数据库实现必须同时校验 run 仍为 RUNNING，以及 execution owner/fencing token
     * 仍然匹配。返回 false 的调用方不得写终态事件、assistant、Interaction 或 binding。</p>
     */
    default boolean tryFenceOwnerTerminalCommit(OwnerTerminalFence fence) {
        if (fence == null || fence.executionClaim() == null) {
            return false;
        }
        return findById(fence.runId())
                .filter(run -> run.status() == ChatRunStatus.RUNNING)
                .filter(run -> fence.tenantId().equals(run.tenantId()))
                .filter(run -> fence.userId().equals(run.userId()))
                .filter(run -> fence.sessionId().equals(run.sessionId()))
                .isPresent();
    }

    /**
     * 原子接收首次 stop，仅允许当前用户的 RUNNING run 进入 CANCELLING。
     */
    default boolean tryMarkCancelling(StopClaim claim) {
        if (claim == null) {
            return false;
        }
        return findByTenantIdAndUserIdAndId(claim.tenantId(), claim.userId(), claim.runId())
                .filter(run -> run.status() == ChatRunStatus.RUNNING)
                .map(run -> save(run.cancelling(claim.reason())))
                .filter(run -> run.status() == ChatRunStatus.CANCELLING)
                .isPresent();
    }

    /**
     * 原子抢占 stop/watchdog 产生的外部终态写入权。
     *
     * <p>生产数据库实现必须通过单条条件 UPDATE 完成抢占；返回 false 的调用方不得再写终态事件。</p>
     */
    default boolean tryClaimExternalTerminal(ExternalTerminalClaim claim) {
        if (claim == null || claim.terminalStatus() == null || !claim.terminalStatus().terminal()) {
            return false;
        }
        return findById(claim.runId())
                .filter(run -> run.status() == ChatRunStatus.RUNNING || run.status() == ChatRunStatus.CANCELLING)
                .isPresent();
    }

    /**
     * 外部终态事件落库后回填最终事件游标。
     */
    default ChatRun finalizeExternalTerminal(ExternalTerminalFinalize command) {
        ChatRun current = findById(command.runId())
                .orElseThrow(() -> new IllegalStateException("run 不存在: " + command.runId()));
        ChatRun terminal = command.terminalStatus() == ChatRunStatus.CANCELLED
                ? current.cancelled(command.sequence())
                : current.failed(command.sequence());
        return save(terminal);
    }

    enum ExternalTerminalGuard {
        NONE,
        RECOVERY,
        ORPHAN_INTERACTION,
        EXECUTION_INIT_FAILURE,
        ORPHAN_RUN_INIT,
        FIRST_EVENT_TIMEOUT
    }

    record ExternalTerminalClaim(
            String runId,
            String tenantId,
            String userId,
            String sessionId,
            ChatRunStatus terminalStatus,
            String cancelReason,
            Instant finishedAt,
            ExternalTerminalGuard guard,
            String recoveredByInstanceId,
            Long fencingToken,
            String interactionId,
            Instant orphanBefore
    ) {
    }

    record ExternalTerminalFinalize(
            String runId,
            String tenantId,
            String userId,
            String sessionId,
            ChatRunStatus terminalStatus,
            long sequence,
            String cancelReason,
            Instant finishedAt
    ) {
    }

    record OwnerTerminalFence(
            String runId,
            String tenantId,
            String userId,
            String sessionId,
            RunExecutionClaim executionClaim
    ) {
    }

    record StopClaim(
            String runId,
            String tenantId,
            String userId,
            String reason,
            Instant requestedAt
    ) {
    }
}
