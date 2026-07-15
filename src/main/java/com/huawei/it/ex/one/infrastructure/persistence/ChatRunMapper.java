package com.huawei.it.ex.one.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * fin_ex_chat_run_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatRunMapper {
    /**
     * 创建 run 业务生命周期记录。
     *
     * @param row run 写入行，包含归属、路由、消息树挂点、事件游标和审计字段。
     * @return 影响行数。
     */
    int insert(ChatRunWriteRow row);

    /**
     * 锁定 continuation run 所属会话，固定 admission 的 session -> interaction -> run 锁顺序。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @return 1 表示会话存在且归属匹配；无匹配行时返回 {@code null}。
     */
    Integer lockSessionForInteractionContinuation(@Param("tenantId") String tenantId,
                                                   @Param("userId") String userId,
                                                   @Param("sessionId") String sessionId);

    /**
     * 锁定并校验当前 Interaction claim，防止 watchdog 回收后迟到实例创建 run。
     *
     * @param interactionId 当前 Interaction 标识。
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param continueRunId claim 对应的 continuation run 标识。
     * @return 1 表示 claim 仍由当前 run 持有；无匹配行时返回 {@code null}。
     */
    Integer lockInteractionContinuationClaim(@Param("interactionId") String interactionId,
                                              @Param("tenantId") String tenantId,
                                              @Param("userId") String userId,
                                              @Param("sessionId") String sessionId,
                                              @Param("continueRunId") String continueRunId);

    /**
     * 更新 run 状态和关联消息，SQL 层会保护已有终态不被迟到事件覆盖。
     *
     * @param row run 更新行，空字段按 SQL 语义尽量保留已有值。
     * @return 影响行数。
     */
    int updateExisting(ChatRunWriteRow row);

    /**
     * 首次 stop 条件更新，只允许 RUNNING 进入 CANCELLING。
     *
     * @param runId run 主键。
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param reason 取消原因。
     * @param requestedAt stop 接收时间。
     * @return 影响行数；1 表示成功进入 CANCELLING。
     */
    int markCancelling(@Param("runId") String runId,
                       @Param("tenantId") String tenantId,
                       @Param("userId") String userId,
                       @Param("reason") String reason,
                       @Param("requestedAt") java.time.Instant requestedAt);

    /**
     * 通过 run 状态和 execution fencing 获取 owner 终态事务的单行栅栏。
     *
     * @param runId run 主键。
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param ownerInstanceId 当前 execution owner 实例标识。
     * @param fencingToken 当前 execution fencing token。
     * @return 影响行数；1 表示当前 owner 获得终态提交权。
     */
    int fenceOwnerTerminalCommit(@Param("runId") String runId,
                                 @Param("tenantId") String tenantId,
                                 @Param("userId") String userId,
                                 @Param("sessionId") String sessionId,
                                 @Param("ownerInstanceId") String ownerInstanceId,
                                 @Param("fencingToken") long fencingToken);

    /**
     * 通过 run 状态和可选 recovery/orphan guard 原子抢占外部终态。
     *
     * @param row 外部终态抢占条件。
     * @return 影响行数；1 表示获得唯一终态写入权。
     */
    int claimExternalTerminal(ChatRunExternalTerminalClaimRow row);

    /**
     * 外部终态事件插入后回填最终事件游标。
     *
     * @param row 外部终态游标和状态信息。
     * @return 影响行数。
     */
    int finalizeExternalTerminal(ChatRunExternalTerminalFinalizeRow row);

    /**
     * 按 runId 查询 run，供内部已有归属上下文的编排逻辑使用。
     *
     * @param runId run 主键。
     * @return run 行；不存在时为 {@code null}。
     */
    ChatRunRow findById(@Param("runId") String runId);

    /**
     * 按 owner 边界查询 run，防止接口层跨用户访问 stop、resume 或状态查询。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param runId run 主键。
     * @return run 行；不存在或不属于当前用户时为 {@code null}。
     */
    ChatRunRow findByOwnerAndId(@Param("tenantId") String tenantId,
                                @Param("userId") String userId,
                                @Param("runId") String runId);

    /**
     * 按 owner 边界批量查询 run，供历史消息等只读装配批量补充 run 派生字段。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param runIds run 主键集合。
     * @return 当前 owner 下存在的 run 行。
     */
    List<ChatRunRow> findByOwnerAndIds(@Param("tenantId") String tenantId,
                                       @Param("userId") String userId,
                                       @Param("runIds") Collection<String> runIds);

    /**
     * 查询指定会话当前仍处于运行中或取消中的 run。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @return active run 行；不存在时为 {@code null}。
     */
    ChatRunRow findActiveBySession(@Param("tenantId") String tenantId,
                                   @Param("userId") String userId,
                                   @Param("sessionId") String sessionId);

    /**
     * 扫描尚未写入 run.started 且没有 execution 的普通 run。
     *
     * @param orphanBefore 只扫描更新时间早于该时刻的 run。
     * @param limit 本轮最多返回数量。
     * @return 按更新时间正序排列的孤儿 run。
     */
    List<ChatRunRow> findExecutionInitOrphans(@Param("orphanBefore") java.time.Instant orphanBefore,
                                              @Param("limit") int limit);
}
