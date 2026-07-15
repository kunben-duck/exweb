package com.huawei.it.ex.one.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.application.integration.memory.RouteMemoryRepository;
import com.huawei.it.ex.one.domain.memory.RouteMemoryItem;
import com.huawei.it.ex.one.domain.memory.RouteMemoryItemStatus;
import com.huawei.it.ex.one.domain.memory.RouteMemoryItemType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * RouteMemory 数据库事实源实现。
 */
@Repository
public class MyBatisRouteMemoryRepository implements RouteMemoryRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final RouteMemoryMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisRouteMemoryRepository(RouteMemoryMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public RouteMemoryItem save(RouteMemoryItem item) {
        mapper.insert(toRow(item));
        return item;
    }

    @Override
    public List<RouteMemoryItem> findRecentRoutes(String tenantId, String userId, String sessionId, int limit) {
        if (blank(tenantId) || blank(userId) || blank(sessionId) || limit <= 0) {
            return List.of();
        }
        return mapper.findRecentRoutes(tenantId, userId, sessionId, limit).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<RouteMemoryItem> findActiveClarifications(String tenantId, String userId, String sessionId) {
        if (blank(tenantId) || blank(userId) || blank(sessionId)) {
            return List.of();
        }
        return mapper.findActiveClarifications(tenantId, userId, sessionId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public boolean latestRouteIsCompletedRelayFallback(String tenantId, String userId, String sessionId) {
        if (blank(tenantId) || blank(userId) || blank(sessionId)) {
            return false;
        }
        return mapper.latestRouteIsCompletedRelayFallback(tenantId, userId, sessionId);
    }

    @Override
    public int foldActiveClarifications(String tenantId, String userId, String sessionId, Instant foldedAt) {
        if (blank(tenantId) || blank(userId) || blank(sessionId)) {
            return 0;
        }
        return mapper.foldActiveClarifications(tenantId, userId, sessionId,
                foldedAt == null ? Instant.now() : foldedAt);
    }

    private RouteMemoryRow toRow(RouteMemoryItem item) {
        RouteMemoryRow row = new RouteMemoryRow();
        row.setId(item.id());
        row.setTenantId(item.tenantId());
        row.setUserId(item.userId());
        row.setSessionId(item.sessionId());
        row.setItemType(item.itemType().name());
        row.setStatus(item.status().name());
        row.setQueryText(item.queryText());
        row.setIntentId(item.intentId());
        row.setIntentName(item.intentName());
        row.setDomainAgentId(item.domainAgentId());
        row.setRouteSource(item.routeSource());
        row.setClarifyQuestion(item.clarifyQuestion());
        row.setClarificationType(item.clarificationType());
        row.setSourceRunId(item.sourceRunId());
        row.setInteractionId(item.interactionId());
        row.setPayloadJson(toJson(item.payload()));
        row.setFoldedAt(item.foldedAt());
        row.setCreatedAt(item.createdAt());
        row.setUpdatedAt(item.updatedAt());
        return row;
    }

    private RouteMemoryItem toDomain(RouteMemoryRow row) {
        return new RouteMemoryItem(
                row.getId(),
                row.getTenantId(),
                row.getUserId(),
                row.getSessionId(),
                RouteMemoryItemType.valueOf(row.getItemType()),
                RouteMemoryItemStatus.valueOf(row.getStatus()),
                row.getQueryText(),
                row.getIntentId(),
                row.getIntentName(),
                row.getDomainAgentId(),
                row.getRouteSource(),
                row.getClarifyQuestion(),
                row.getClarificationType(),
                row.getSourceRunId(),
                row.getInteractionId(),
                fromJson(row.getPayloadJson()),
                row.getFoldedAt(),
                row.getCreatedAt(),
                row.getUpdatedAt()
        );
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("序列化 RouteMemory payload 失败", ex);
        }
    }

    private Map<String, Object> fromJson(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("反序列化 RouteMemory payload 失败", ex);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
