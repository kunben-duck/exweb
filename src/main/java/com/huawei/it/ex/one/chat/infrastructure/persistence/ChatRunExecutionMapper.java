package com.huawei.it.ex.one.chat.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * fin_ex_chat_run_execution_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatRunExecutionMapper {
    /**
     * 创建 run 执行控制面记录。
     *
     * @param row execution 写入行，包含 owner 实例、租约、fencing token 和恢复扩展信息。
     * @return 影响行数。
     */
    int insert(ChatRunExecutionWriteRow row);

    /**
     * 通过 run 行短更新串行化 Interaction execution 初始化与孤儿回收。
     *
     * @param runId continuation run 标识。
     * @param interactionId 当前应持有该 run 的 Interaction 标识。
     * @return 影响行数；1 表示仍允许创建 execution。
     */
    int claimInteractionExecutionInitialization(@Param("runId") String runId,
                                                 @Param("interactionId") String interactionId);

    /**
     * 查询指定 run 的执行控制面状态。
     *
     * @param runId run 主键。
     * @return execution 行；不存在时为 {@code null}。
     */
    ChatRunExecutionRow findByRunId(@Param("runId") String runId);

    /**
     * 同时校验 run 和 execution 仍由当前 claim 持有。
     *
     * @param runId run 主键。
     * @param ownerInstanceId 当前执行实例 ID。
     * @param fencingToken 当前 execution fencing token。
     * @return 匹配记录数，通常为 0 或 1。
     */
    int countCurrentOwnerRunning(@Param("runId") String runId,
                                 @Param("ownerInstanceId") String ownerInstanceId,
                                 @Param("fencingToken") long fencingToken);

    /**
     * 刷新当前 owner 持有的 run 租约。
     *
     * @param runId run 主键。
     * @param ownerInstanceId 当前执行实例 ID，必须与数据库 owner 匹配。
     * @param leaseUntil 新的租约截止时间。
     * @return 影响行数；为 0 表示 owner 失效或 execution 不在可续约状态。
     */
    int heartbeat(@Param("runId") String runId,
                  @Param("ownerInstanceId") String ownerInstanceId,
                  @Param("leaseUntil") Instant leaseUntil);

    /**
     * 将 execution 标记为终态。
     *
     * @param runId run 主键。
     * @param terminalStatus 终态值，例如 COMPLETED、FAILED 或 CANCELLED。
     * @return 影响行数。
     */
    int markTerminal(@Param("runId") String runId,
                     @Param("terminalStatus") String terminalStatus);

    /**
     * 扫描运行租约已过期的 execution。
     *
     * @param limit 本轮最多返回数量。
     * @return 按租约过期时间正序排列的 stale execution。
     */
    List<ChatRunExecutionRow> findLeaseExpired(@Param("limit") int limit);

    /**
     * 扫描恢复租约已过期的 execution。
     *
     * @param limit 本轮最多返回数量。
     * @return 按恢复租约过期时间正序排列的 stale recovering execution。
     */
    List<ChatRunExecutionRow> findRecoveryExpired(@Param("limit") int limit);

    /**
     * 条件抢占过期 execution 并递增 fencing token。
     *
     * @param runId run 主键。
     * @param recoveredByInstanceId 当前尝试恢复的实例 ID。
     * @param strategy 本次恢复策略。
     * @param recoveryLeaseUntil 恢复租约截止时间。
     * @return 影响行数；只有返回 1 的实例抢占成功。
     */
    int tryClaimRecovering(@Param("runId") String runId,
                           @Param("recoveredByInstanceId") String recoveredByInstanceId,
                           @Param("strategy") String strategy,
                           @Param("recoveryLeaseUntil") Instant recoveryLeaseUntil);

    /**
     * Runtime takeover 成功后，将 execution 从 RECOVERING 切回 RUNNING。
     *
     * @param runId run 主键。
     * @param ownerInstanceId 新 owner 实例 ID。
     * @param leaseUntil 新运行租约截止时间。
     * @return 影响行数。
     */
    int markTakeoverRunning(@Param("runId") String runId,
                            @Param("ownerInstanceId") String ownerInstanceId,
                            @Param("leaseUntil") Instant leaseUntil);

    /**
     * 判断指定 run 的运行租约是否已过期。
     *
     * @param runId run 主键。
     * @return 过期运行租约数量，通常为 0 或 1。
     */
    int countLeaseExpired(@Param("runId") String runId);
}
