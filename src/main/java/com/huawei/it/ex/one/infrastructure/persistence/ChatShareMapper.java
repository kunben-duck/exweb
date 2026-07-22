package com.huawei.it.ex.one.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * fin_ex_chat_share_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatShareMapper {
    /**
     * 创建单轮问答分享快照。
     *
     * @param row 分享写入行，包含来源消息、分享状态、过期时间和 snapshotJson。
     * @return 影响行数。
     */
    int insert(ChatShareRow row);

    /**
     * 更新分享状态、过期时间或快照内容。
     *
     * @param row 分享更新行，id、tenantId、ownerUserId 用于限定创建者归属。
     * @return 影响行数。
     */
    int update(ChatShareRow row);

    /**
     * 按分享 ID 查询分享。
     *
     * @param shareId 分享主键。
     * @return 分享行；不存在时为 {@code null}。
     */
    ChatShareRow findById(@Param("shareId") String shareId);

    /**
     * 统计当前用户创建的分享数量。
     *
     * @param tenantId 租户标识。
     * @param ownerUserId 分享创建者用户标识。
     * @return 分享总数。
     */
    long countByOwner(@Param("tenantId") String tenantId,
                      @Param("ownerUserId") String ownerUserId);

    /**
     * 页码式查询当前用户创建的分享列表。
     *
     * @param tenantId 租户标识。
     * @param ownerUserId 分享创建者用户标识。
     * @param limit 本页最大返回数量。
     * @param offset 分页偏移量。
     * @return 分享列表。
     */
    List<ChatShareRow> findPageByOwner(@Param("tenantId") String tenantId,
                                       @Param("ownerUserId") String ownerUserId,
                                       @Param("limit") int limit,
                                       @Param("offset") long offset);

    /**
     * 批量撤销指定会话下仍处于 ACTIVE 的分享。
     *
     * @param tenantId 租户标识。
     * @param ownerUserId 分享创建者用户标识。
     * @param sessionId 被删除或撤销访问的源会话标识。
     * @param revokedAt 撤销时间。
     * @param updatedAt 更新时间。
     * @return 影响行数。
     */
    int revokeActiveBySession(@Param("tenantId") String tenantId,
                              @Param("ownerUserId") String ownerUserId,
                              @Param("sessionId") String sessionId,
                              @Param("revokedAt") Instant revokedAt,
                              @Param("updatedAt") Instant updatedAt);
}
