package com.huawei.it.ex.one.application.integration.memory;

import com.huawei.it.ex.one.domain.memory.RouteMemoryItem;

import java.time.Instant;
import java.util.List;

/**
 * RouteMemory 持久化仓储。
 */
public interface RouteMemoryRepository {
    RouteMemoryItem save(RouteMemoryItem item);

    /**
     * 查询可发送给意图服务的最近路由记录，不包含前端直选路由。
     *
     * <p>过滤必须发生在 TopK 限制之前，避免前端直选事实占用意图 history 配额。</p>
     */
    List<RouteMemoryItem> findRecentRoutes(String tenantId, String userId, String sessionId, int limit);

    List<RouteMemoryItem> findActiveClarifications(String tenantId, String userId, String sessionId);

    /**
     * 判断当前会话最新一条路由是否为已正常完成的 Relay fallback。
     *
     * <p>必须先确定最新 route，再关联其 source run 状态；不能向前回退查找更早的 completed Relay。</p>
     */
    boolean latestRouteIsCompletedRelayFallback(String tenantId, String userId, String sessionId);

    int foldActiveClarifications(String tenantId, String userId, String sessionId, Instant foldedAt);
}
