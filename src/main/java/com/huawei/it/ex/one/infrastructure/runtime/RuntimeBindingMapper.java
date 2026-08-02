package com.huawei.it.ex.one.infrastructure.runtime;

import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * fin_ex_runtime_binding_t 的 MyBatis mapper。
 */
@Mapper
public interface RuntimeBindingMapper {
    /**
     * 创建消息树 leaf 与下游 Runtime session 的绑定。
     *
     * @param row binding 写入行，包含归属、provider、leaf、runtimeSessionId、状态和过期时间。
     * @return 影响行数。
     */
    int insert(RuntimeBindingRow row);

    /**
     * 更新 RuntimeBinding 状态和续接信息。
     *
     * @param row binding 更新行，id 定位记录。
     * @return 影响行数。
     */
    int update(RuntimeBindingRow row);

    /**
     * 锁定并校验 Interaction continuation 的 run/execution 写入权。
     *
     * @param row 待更新 Binding 的可信归属。
     * @param claim 当前 execution 写入权。
     * @return 1 表示 run/execution 仍由当前 claim 持有，否则为空。
     */
    Integer lockInteractionResumeExecution(@Param("row") RuntimeBindingRow row,
                                           @Param("claim") RunExecutionClaim claim);

    /**
     * 条件刷新仍由等待态来源 run 持有的 ACTIVE Relay Binding。
     *
     * @param row 待刷新 Binding 的可信字段。
     * @param expectedLastRunId 等待态来源 run 标识。
     * @return 影响行数。
     */
    int updateInteractionResume(@Param("row") RuntimeBindingRow row,
                                @Param("expectedLastRunId") String expectedLastRunId);

    /**
     * run-B 未启动 Runtime 时条件恢复等待态来源 run。
     *
     * @param bindingId RuntimeBinding 主键。
     * @param continueRunId 尚未启动 Runtime 的 continuation run 标识。
     * @param sourceRunId 等待态来源 run 标识。
     * @return 影响行数。
     */
    int restoreInteractionResume(@Param("bindingId") String bindingId,
                                 @Param("continueRunId") String continueRunId,
                                 @Param("sourceRunId") String sourceRunId);

    /**
     * Runtime 尚未订阅时条件恢复激活前的 Binding 快照。
     *
     * @param row 激活前的完整 Binding 快照。
     * @param currentRunId 尚未启动 Runtime 的当前 run 标识。
     * @return 影响行数。
     */
    int restoreUnstartedForRun(@Param("row") RuntimeBindingRow row,
                               @Param("currentRunId") String currentRunId);

    /**
     * 条件取消仍由指定 run 持有的 ACTIVE RuntimeBinding。
     *
     * @param bindingId RuntimeBinding 主键。
     * @param runId 当前绑定记录的最近 run 标识。
     * @return 影响行数。
     */
    int cancelActiveForRun(@Param("bindingId") String bindingId,
                           @Param("runId") String runId);

    /**
     * 条件取消 Interaction 引用且仍由来源或 continuation run 持有的 ACTIVE binding。
     *
     * @param row Binding 主键及可信归属字段。
     * @param sourceRunId 等待态来源 run 标识。
     * @param continueRunId 当前 continuation run 标识，可为空。
     * @return 影响行数。
     */
    int cancelActiveForInteraction(@Param("row") RuntimeBindingRow row,
                                   @Param("sourceRunId") String sourceRunId,
                                   @Param("continueRunId") String continueRunId);

    /**
     * 按主键查询 RuntimeBinding。
     *
     * @param bindingId 绑定主键。
     * @return binding 行；不存在时为 {@code null}。
     */
    RuntimeBindingRow findById(@Param("bindingId") String bindingId);

    /**
     * 查询指定 leaf 的 active RuntimeBinding。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId ChatService 会话标识。
     * @param provider Runtime provider。
     * @param leafMessageId 消息树 leaf；为空表示绑定在空 leaf 上。
     * @return active binding；不存在时为 {@code null}。
     */
    RuntimeBindingRow findActive(@Param("tenantId") String tenantId,
                                 @Param("userId") String userId,
                                 @Param("sessionId") String sessionId,
                                 @Param("provider") String provider,
                                 @Param("leafMessageId") String leafMessageId);

    /**
     * 查询会话下指定 provider 的 active RuntimeBinding。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId ChatService 会话标识。
     * @param provider Runtime provider。
     * @return active binding 列表。
     */
    List<RuntimeBindingRow> findActiveBySession(@Param("tenantId") String tenantId,
                                                @Param("userId") String userId,
                                                @Param("sessionId") String sessionId,
                                                @Param("provider") String provider);

    /**
     * 查询会话下所有 provider 的 active RuntimeBinding。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId ChatService 会话标识。
     * @return active binding 列表，按更新时间倒序。
     */
    List<RuntimeBindingRow> findActiveBySessionAnyProvider(@Param("tenantId") String tenantId,
                                                           @Param("userId") String userId,
                                                           @Param("sessionId") String sessionId);

    /**
     * 查询会话下指定 provider 的可恢复 RuntimeBinding，按更新时间倒序。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId ChatService 会话标识。
     * @param provider Runtime provider。
     * @return 可恢复 binding 列表。
     */
    List<RuntimeBindingRow> findResumableBySession(@Param("tenantId") String tenantId,
                                                   @Param("userId") String userId,
                                                   @Param("sessionId") String sessionId,
                                                   @Param("provider") String provider);
}
