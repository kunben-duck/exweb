package com.huawei.it.ex.one.application.integration.conversation;

import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ChatRun 执行控制面事实源端口。
 *
 * <p>该端口负责维护 run 的实例归属、心跳、租约、恢复抢占和 fencing token。
 *数据库实现是最终正确性来源；Redis recover lock 只能作为优化。</p>
 */
public interface ChatRunExecutionRepository {
    /**
     * 为新创建的业务 run 初始化执行控制面记录。
     *
     * @param run 业务 run 快照。
     * @param executionId execution 主键。
     * @param ownerInstanceId 当前执行实例 ID。
     * @param leaseDuration 执行租约时长。
     * @return 创建后的 execution 快照。
     */
    ChatRunExecution createForRun(ChatRun run, String executionId, String ownerInstanceId, Duration leaseDuration);

    /**
     * 为 Interaction continuation 初始化 execution，并再次校验对应 claim 仍有效。
     */
    default ChatRunExecution createForInteractionRun(ChatRun run, String executionId, String ownerInstanceId,
                                                      Duration leaseDuration, String interactionId) {
        return createForRun(run, executionId, ownerInstanceId, leaseDuration);
    }

    /**
     * 按 runId 查询执行控制面快照。
     *
     * @param runId run 标识。
     * @return execution 快照，不存在时为空。
     */
    Optional<ChatRunExecution> findByRunId(String runId);

    /**
     * 校验当前 claim 仍拥有 RUNNING run/execution。
     *
     * <p>生产实现应在一条只读 SQL 中同时校验 run 状态、owner 和 fencing token。</p>
     */
    default boolean isCurrentOwnerRunning(RunExecutionClaim claim) {
        if (claim == null) {
            return false;
        }
        return findByRunId(claim.runId())
                .filter(execution -> execution.executionStatus() == ChatRunExecutionStatus.RUNNING)
                .filter(execution -> claim.ownerInstanceId().equals(execution.ownerInstanceId()))
                .filter(execution -> claim.fencingToken() == execution.fencingToken())
                .isPresent();
    }

    /**
     * owner 实例刷新 run 租约。
     *
     * @param runId run 标识。
     * @param ownerInstanceId 当前 owner 实例 ID。
     * @param fencingToken 当前 execution fencing token。
     * @param leaseDuration 新租约时长。
     * @return 是否刷新成功。
     */
    boolean heartbeat(String runId, String ownerInstanceId, long fencingToken, Duration leaseDuration);

    /**
     * 批量刷新当前 owner 持有的 run 租约。
     *
     * <p>默认实现保持测试替身和兼容实现可用；生产数据库实现应使用单条批量 UPDATE。</p>
     *
     * @param claims 本批 execution 写入权声明。
     * @param leaseDuration 新租约时长。
     * @return 数据库确认仍有效并完成续租的 claim。
     */
    default List<RunExecutionClaim> heartbeatBatch(List<RunExecutionClaim> claims, Duration leaseDuration) {
        if (claims == null || claims.isEmpty()) {
            return List.of();
        }
        List<RunExecutionClaim> renewed = new ArrayList<>(claims.size());
        for (RunExecutionClaim claim : claims) {
            if (claim != null && heartbeat(
                    claim.runId(), claim.ownerInstanceId(), claim.fencingToken(), leaseDuration)) {
                renewed.add(claim);
            }
        }
        return List.copyOf(renewed);
    }

    /**
     * run已进入CANCELLING但owner尚未接受收口时，只缩短当前RUNNING execution租约。
     */
    default boolean shortenLeaseForCancellingRun(String runId, Duration leaseDuration) {
        return false;
    }

    /**
     * 当前owner接受stop并取得独占终态收口权。
     *
     * <p>生产实现必须校验run归属、CANCELLING状态以及owner/fencing，并原子地把execution从
     * RUNNING更新为CANCELLING。</p>
     */
    default boolean markOwnerStopAccepted(ChatRun run, RunExecutionClaim claim, Duration leaseDuration) {
        return false;
    }

    /**
     * 将 execution 同步到 run 终态。
     *
     * @param runId run 标识。
     * @param terminalStatus 终态。
     * @return 是否更新成功。
     */
    boolean markTerminal(String runId, ChatRunExecutionStatus terminalStatus);

    /**
     * 查询运行租约已过期的 execution 候选。
     *
     * @param limit 最大返回数量。
     * @return stale execution 列表。
     */
    List<ChatRunExecution> findLeaseExpired(int limit);

    /**
     * 查询恢复过程自身已过期的 execution 候选。
     *
     * @param limit 最大返回数量。
     * @return stale recovering execution 列表。
     */
    List<ChatRunExecution> findRecoveryExpired(int limit);

    /**
     * 尝试把 stale execution 抢占为 RECOVERING。
     *
     * @param runId run 标识。
     * @param recoveredByInstanceId 当前执行恢复的实例 ID。
     * @param strategy 恢复策略名。
     * @param recoveryLeaseDuration 恢复租约时长。
     * @return 抢占成功后的 execution；失败时为空。
     */
    Optional<ChatRunExecution> tryClaimRecovering(String runId, String recoveredByInstanceId,
                                                 String strategy, Duration recoveryLeaseDuration);

    /**
     * Runtime takeover 成功后切换 owner 并恢复 RUNNING 租约。
     *
     * @param runId run 标识。
     * @param ownerInstanceId 新 owner 实例 ID。
     * @param leaseDuration 运行租约时长。
     * @return 切换成功后的 execution；失败时为空。
     */
    Optional<ChatRunExecution> markTakeoverRunning(String runId, String ownerInstanceId, Duration leaseDuration);

    /**
     * 判断 execution 是否已经超出租约。
     *
     * @param runId run 标识。
     * @param now 当前应用时间；实现应优先使用 DB 时间判断，参数只作测试或兜底。
     * @return true 表示该 run 的执行租约已经过期。
     */
    boolean isLeaseExpired(String runId, Instant now);
}
