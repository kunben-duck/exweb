package com.huawei.it.ex.one.domain.memory;

import java.time.Instant;
import java.util.Map;

/**
 * 会话路由记忆事实。
 *
 * <p>RouteMemory 只服务意图路由上下文，不参与普通短期/长期语义记忆检索。</p>
 */
public record RouteMemoryItem(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        RouteMemoryItemType itemType,
        RouteMemoryItemStatus status,
        String queryText,
        String intentId,
        String intentName,
        String domainAgentId,
        String routeSource,
        String clarifyQuestion,
        String clarificationType,
        String sourceRunId,
        String interactionId,
        Map<String, Object> payload,
        Instant foldedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public RouteMemoryItem {
        status = status == null ? RouteMemoryItemStatus.ACTIVE : status;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
