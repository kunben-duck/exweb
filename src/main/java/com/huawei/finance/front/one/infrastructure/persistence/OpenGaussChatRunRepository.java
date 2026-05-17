package com.huawei.finance.front.one.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * ChatRun 的 openGauss 事实源实现。
 */
@Repository
public class OpenGaussChatRunRepository implements ChatRunRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ChatRunMapper mapper;
    private final ObjectMapper objectMapper;

    public OpenGaussChatRunRepository(ChatRunMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatRun save(ChatRun run) {
        mapper.upsert(
                run.id(),
                run.tenantId(),
                run.userId(),
                run.sessionId(),
                run.status().name(),
                run.routeType(),
                run.agentCode(),
                run.runtimeProvider(),
                run.runtimeSessionId(),
                run.firstSeq(),
                run.lastSeq(),
                run.cancelReason(),
                run.startedAt(),
                run.finishedAt(),
                toJson(run.metadata()),
                run.createdAt(),
                run.updatedAt()
        );
        return findById(run.id()).orElse(run);
    }

    @Override
    public Optional<ChatRun> findById(String runId) {
        return Optional.ofNullable(mapper.findById(runId)).map(this::toDomain);
    }

    @Override
    public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
        return Optional.ofNullable(mapper.findByOwnerAndId(tenantId, userId, runId)).map(this::toDomain);
    }

    @Override
    public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) {
        return Optional.ofNullable(mapper.findActiveBySession(tenantId, userId, sessionId)).map(this::toDomain);
    }

    private ChatRun toDomain(ChatRunRow row) {
        return new ChatRun(
                row.getId(),
                row.getTenantId(),
                row.getUserId(),
                row.getSessionId(),
                ChatRunStatus.valueOf(row.getStatus()),
                row.getRouteType(),
                row.getAgentCode(),
                row.getRuntimeProvider(),
                row.getRuntimeSessionId(),
                row.getFirstSeq(),
                row.getLastSeq(),
                row.getCancelReason(),
                row.getStartedAt(),
                row.getFinishedAt(),
                fromJson(row.getMetadataJson()),
                row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt(),
                row.getUpdatedAt() == null ? Instant.EPOCH : row.getUpdatedAt()
        );
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("ChatRun metadata 序列化失败", ex);
        }
    }

    private Map<String, Object> fromJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }
}
