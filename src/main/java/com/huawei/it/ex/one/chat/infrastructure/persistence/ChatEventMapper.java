package com.huawei.it.ex.one.chat.infrastructure.persistence;

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
     * 一次预取多个全局事件序号。
     *
     * @param count 需要分配的序号数量。
     * @return 按生成顺序排列的事件序号。
     */
    List<Long> nextSeqs(@Param("count") int count);

    /**
     * 查询事件所属 run/session 的可信归属，供无 execution claim 的控制事件写入使用。
     *
     * @param sessionId 事件所属会话。
     * @param runId 事件所属 run。
     * @return 可信归属上下文；归属不匹配时返回 {@code null}。
     */
    ChatEventAppendContextRow findEventAppendContext(@Param("sessionId") String sessionId,
                                                     @Param("runId") String runId);

    /**
     * 在普通事件写入前获取 run/execution 行共享锁，并同步校验 execution owner/fencing。
     *
     * @param sessionId 事件所属会话。
     * @param runId 事件所属 run。
     * @param ownerInstanceId 当前 execution owner 实例。
     * @param fencingToken 当前 execution fencing token。
     * @return 可信归属上下文；无匹配行时返回 {@code null}。
     */
    ChatEventAppendContextRow lockRunForEventAppend(@Param("sessionId") String sessionId,
                                                    @Param("runId") String runId,
                                                    @Param("ownerInstanceId") String ownerInstanceId,
                                                    @Param("fencingToken") long fencingToken);

    /**
     * 使用已经过数据库栅栏校验的可信归属追加单条事件。
     *
     * @param row 事件写入行，包含可信 tenant/user/session/run 归属及标准事件字段。
     * @return 影响行数。
     */
    int insert(ChatEventWriteRow row);

    /**
     * 使用已经过数据库栅栏校验的可信归属批量追加同一 run 的流式事件。
     *
     * @param rows 已分配主键、序号及序列化 payload 的有序事件行。
     * @return 实际插入行数，必须与 rows 数量一致。
     */
    int insertBatch(@Param("rows") List<ChatEventWriteRow> rows);

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
