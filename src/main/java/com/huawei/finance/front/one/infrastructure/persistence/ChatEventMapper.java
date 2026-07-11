package com.huawei.finance.front.one.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * fin_ex_chat_event_t 的 MyBatis Mapper。
 *
 * <p>写入时同时校验 session 与 run 的 tenant/user/session 归属，保证事件事实源不会被
 * 错误 runId 或 sessionId 污染。</p>
 */
@Mapper
public interface ChatEventMapper {
    /**
     * 从数据库 sequence 预取事件游标。
     *
     * @return 下一个全局事件序号。
     */
    Long nextSeq();

    /**
     * 在普通事件写入前获取 run 行共享锁，并同步校验 execution owner/fencing。
     *
     * @param sessionId 事件所属会话。
     * @param runId 事件所属 run。
     * @param ownerInstanceId 当前 execution owner 实例。
     * @param fencingToken 当前 execution fencing token。
     * @return 1 表示准入成功；无匹配行时返回 {@code null}。
     */
    Integer lockRunForEventAppend(@Param("sessionId") String sessionId,
                                  @Param("runId") String runId,
                                  @Param("ownerInstanceId") String ownerInstanceId,
                                  @Param("fencingToken") long fencingToken);

    /**
     * 追加已完成外层校验的事件，SQL 仍会通过 session/run join 做归属兜底。
     *
     * @param row 事件写入行，包含 sessionId、runId、seq、eventType、payloadJson 和 createdAt。
     * @return 影响行数；为 0 表示 run/session 归属不匹配。
     */
    int insertFromSession(ChatEventWriteRow row);

    /**
     * 追加流式事件，并在同一条 SQL 内校验 execution owner 和 fencing token。
     *
     * @param row 事件写入行，除事件字段外还包含 ownerInstanceId 和 fencingToken。
     * @return 影响行数；为 0 表示 run 已非 RUNNING、owner 失效或 fencing token 失效。
     */
    int insertFromSessionWithExecutionGuard(ChatEventWriteRow row);

    /**
     * 查询指定会话在某个事件游标之后的事件，用于 session 级 Event Resume。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param afterSeq 恢复游标，仅返回 seq 大于该值的事件。
     * @return 按 seq 正序排列的事件列表。
     */
    List<ChatEventRow> findByOwnerAndSessionAfterSeq(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("afterSeq") long afterSeq
    );

    /**
     * 查询指定 run 在某个事件游标之后的事件，用于 run 级 Event Resume。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识，作为 run 查询的额外隔离边界。
     * @param runId run 标识。
     * @param afterSeq 恢复游标，仅返回 seq 大于该值的事件。
     * @return 按 seq 正序排列的事件列表。
     */
    List<ChatEventRow> findByOwnerAndRunAfterSeq(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("runId") String runId,
            @Param("afterSeq") long afterSeq
    );

    /**
     * 查询指定会话当前最新事件序号。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @return 最新 seq；没有事件时返回 0。
     */
    long findLatestSeqByOwnerAndSession(@Param("tenantId") String tenantId,
                                        @Param("userId") String userId,
                                        @Param("sessionId") String sessionId);
}
