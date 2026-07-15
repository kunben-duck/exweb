package com.huawei.it.ex.one.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * fin_ex_route_memory_t 的 MyBatis Mapper。
 */
@Mapper
public interface RouteMemoryMapper {
    /**
     * 写入一条 RouteMemory 事实记录。
     *
     * @param row 路由记忆行，包含归属、条目类型、状态、展示摘要和 payload。
     * @return 写入行数。
     */
    int insert(RouteMemoryRow row);

    /**
     * 查询当前会话最近已生效路由摘要，用于组装意图服务 conversationContext.history。
     *
     * @param tenantId 租户边界。
     * @param userId 用户边界。
     * @param sessionId 会话边界。
     * @param limit 最大返回条数。
     * @return 最近成功路由记录，按创建时间倒序返回。
     */
    List<RouteMemoryRow> findRecentRoutes(@Param("tenantId") String tenantId,
                                          @Param("userId") String userId,
                                          @Param("sessionId") String sessionId,
                                          @Param("limit") int limit);

    /**
     * 查询当前会话尚未折叠的意图澄清链路。
     *
     * @param tenantId 租户边界。
     * @param userId 用户边界。
     * @param sessionId 会话边界。
     * @return active clarify 记录，按创建时间正序返回。
     */
    List<RouteMemoryRow> findActiveClarifications(@Param("tenantId") String tenantId,
                                                  @Param("userId") String userId,
                                                  @Param("sessionId") String sessionId);

    /**
     * 判断最新 route 是否为 source run 已正常完成的 Relay fallback。
     *
     * @param tenantId 租户边界。
     * @param userId 用户边界。
     * @param sessionId 会话边界。
     * @return 最新 route 是已完成 Relay fallback 时返回 true。
     */
    boolean latestRouteIsCompletedRelayFallback(@Param("tenantId") String tenantId,
                                                 @Param("userId") String userId,
                                                 @Param("sessionId") String sessionId);

    /**
     * 将当前会话未完成的意图澄清记录标记为已折叠。
     *
     * @param tenantId 租户边界。
     * @param userId 用户边界。
     * @param sessionId 会话边界。
     * @param foldedAt 折叠时间。
     * @return 更新行数。
     */
    int foldActiveClarifications(@Param("tenantId") String tenantId,
                                 @Param("userId") String userId,
                                 @Param("sessionId") String sessionId,
                                 @Param("foldedAt") Instant foldedAt);
}
