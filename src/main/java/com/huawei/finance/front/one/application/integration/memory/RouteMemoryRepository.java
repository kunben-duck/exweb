package com.huawei.finance.front.one.application.integration.memory;

import com.huawei.finance.front.one.domain.memory.RouteMemoryItem;
import java.time.Instant;
import java.util.List;

/**
 * RouteMemory 持久化仓储。
 */
public interface RouteMemoryRepository {
    RouteMemoryItem save(RouteMemoryItem item);

    List<RouteMemoryItem> findRecentRoutes(String tenantId, String userId, String sessionId, int limit);

    List<RouteMemoryItem> findActiveClarifications(String tenantId, String userId, String sessionId);

    int foldActiveClarifications(String tenantId, String userId, String sessionId, Instant foldedAt);
}
