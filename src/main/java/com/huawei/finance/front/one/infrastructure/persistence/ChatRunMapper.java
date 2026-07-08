package com.huawei.finance.front.one.infrastructure.persistence;

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
     * 更新 run 状态和关联消息，SQL 层会保护已有终态不被迟到事件覆盖。
     *
     * @param row run 更新行，空字段按 SQL 语义尽量保留已有值。
     * @return 影响行数。
     */
    int updateExisting(ChatRunWriteRow row);

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
}
