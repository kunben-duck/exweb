package com.huawei.it.ex.one.infrastructure.session;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * fin_ex_chat_session_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatSessionMapper {
    /**
     * 新建聊天会话。
     *
     * @param row 会话写入行，包含归属、状态、标题、消息树 leaf 和分支信息。
     * @return 影响行数。
     */
    int insert(ChatSessionRow row);

    /**
     * 按归属更新会话基础信息和消息树状态。
     *
     * @param row 会话更新行，id、tenantId、userId 用于限定归属。
     * @return 影响行数。
     */
    int update(ChatSessionRow row);

    /**
     * 按会话 ID 查询会话。
     *
     * @param sessionId 会话标识。
     * @return 会话行；不存在时为 {@code null}。
     */
    ChatSessionRow findById(@Param("sessionId") String sessionId);

    /**
     * 按 owner 边界查询会话。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @return 会话行；不存在或不属于当前用户时为 {@code null}。
     */
    ChatSessionRow findByOwnerAndId(@Param("tenantId") String tenantId,
                                    @Param("userId") String userId,
                                    @Param("sessionId") String sessionId);

    /**
     * 查询当前用户全部会话。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @return 会话列表。
     */
    List<ChatSessionRow> findByOwner(@Param("tenantId") String tenantId, @Param("userId") String userId);

    /**
     * 查询当前用户非删除会话中的应用分类。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @return 已去重并按最近活动时间倒序排列的应用分类。
     */
    List<ChatSessionAppRow> findAppsByOwner(@Param("tenantId") String tenantId,
                                            @Param("userId") String userId);

    /**
     * 游标分页查询当前用户未删除会话。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param appId 可选应用标识过滤条件。
     * @param titlePattern 可选、已转义的标题包含匹配参数。
     * @param cursorUpdatedAt 上一页最后一条会话的更新时间，可为空。
     * @param cursorId 上一页最后一条会话 ID，可为空。
     * @param limit 最大返回条数。
     * @return 会话列表。
     */
    List<ChatSessionRow> findPageByOwner(@Param("tenantId") String tenantId,
                                         @Param("userId") String userId,
                                         @Param("appId") String appId,
                                         @Param("titlePattern") String titlePattern,
                                         @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                         @Param("cursorId") String cursorId,
                                         @Param("limit") int limit);

    /**
     * 统计当前用户未删除会话数量。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param appId 可选应用标识过滤条件。
     * @param titlePattern 可选、已转义的标题包含匹配参数。
     * @return 会话总数。
     */
    long countPageByOwner(@Param("tenantId") String tenantId,
                          @Param("userId") String userId,
                          @Param("appId") String appId,
                          @Param("titlePattern") String titlePattern);

    /**
     * 页码式查询当前用户未删除会话。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param appId 可选应用标识过滤条件。
     * @param titlePattern 可选、已转义的标题包含匹配参数。
     * @param limit 本页最大返回数量。
     * @param offset 分页偏移量。
     * @return 会话列表。
     */
    List<ChatSessionRow> findNumberPageByOwner(@Param("tenantId") String tenantId,
                                               @Param("userId") String userId,
                                               @Param("appId") String appId,
                                               @Param("titlePattern") String titlePattern,
                                               @Param("limit") int limit,
                                               @Param("offset") long offset);

    /**
     * 锁定会话行并读取当前消息节点序号水位。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @return 当前最大 node_order；会话不存在时为 {@code null}。
     */
    Long lockNodeOrder(@Param("tenantId") String tenantId,
                       @Param("userId") String userId,
                       @Param("sessionId") String sessionId);

    /**
     * 更新会话消息节点序号水位。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param lastNodeOrder 新的最大 node_order。
     * @param updatedAt 更新时间。
     * @return 影响行数。
     */
    int updateNodeOrder(@Param("tenantId") String tenantId,
                        @Param("userId") String userId,
                        @Param("sessionId") String sessionId,
                        @Param("lastNodeOrder") long lastNodeOrder,
                        @Param("updatedAt") Instant updatedAt);

    /**
     * 更新会话当前 active path 的叶子消息。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param leafMessageId 新的 leaf message ID。
     * @param updatedAt 更新时间。
     * @return 影响行数。
     */
    int updateCurrentLeaf(@Param("tenantId") String tenantId,
                          @Param("userId") String userId,
                          @Param("sessionId") String sessionId,
                          @Param("leafMessageId") String leafMessageId,
                          @Param("updatedAt") Instant updatedAt);

    /**
     * 推进最新可见消息水位，不修改会话 updated_at。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param messageSeq 最新可见 assistant 消息对应的事件 sequence。
     * @return 影响行数。
     */
    int advanceLatestMessageSeq(@Param("tenantId") String tenantId,
                                @Param("userId") String userId,
                                @Param("sessionId") String sessionId,
                                @Param("messageSeq") long messageSeq);

    /**
     * 原子推进已读水位，并限制其不能超过当前最新消息水位。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param readThroughSeq 前端已经实际展示到的事件 sequence。
     * @return 影响行数。
     */
    int markReadThrough(@Param("tenantId") String tenantId,
                        @Param("userId") String userId,
                        @Param("sessionId") String sessionId,
                        @Param("readThroughSeq") long readThroughSeq);
}
