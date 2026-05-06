package com.huawei.finance.front.one.infrastructure.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.task.TaskCardRepository;
import com.huawei.finance.front.one.domain.task.RequiredInput;
import com.huawei.finance.front.one.domain.task.TaskCard;
import com.huawei.finance.front.one.domain.task.TaskStatus;
import com.huawei.finance.front.one.infrastructure.task.mybatis.TaskCardMapper;
import com.huawei.finance.front.one.infrastructure.task.mybatis.TaskCardRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * TaskCard 的 openGauss 仓储实现。
 *
 * <p>TaskCard 是任务状态事实源，所有状态迁移必须先写入本仓储，再刷新 Redis 热缓存。</p>
 */
@Repository
public class OpenGaussTaskCardRepository implements TaskCardRepository {
    private static final TypeReference<List<RequiredInput>> REQUIRED_INPUTS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final TaskCardMapper mapper;
    private final ObjectMapper objectMapper;

    public OpenGaussTaskCardRepository(TaskCardMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<TaskCard> findActive(String tenantId, String userId, String sessionId) {
        return Optional.ofNullable(mapper.findActive(tenantId, userId, sessionId)).map(this::toDomain);
    }

    @Override
    public Optional<TaskCard> findByTaskId(String tenantId, String userId, String sessionId, String taskId) {
        return Optional.ofNullable(mapper.findByTaskId(tenantId, userId, sessionId, taskId)).map(this::toDomain);
    }

    @Override
    public TaskCard save(TaskCard taskCard) {
        mapper.upsert(
                taskCard.taskId(),
                taskCard.tenantId(),
                taskCard.userId(),
                taskCard.chatSessionId(),
                taskCard.bindingId(),
                taskCard.taskGoal(),
                taskCard.taskDomain(),
                taskCard.agentCode(),
                taskCard.agentSessionId(),
                taskCard.taskStatus().name(),
                taskCard.rawNormalizedStatus().name(),
                toJson(taskCard.requiredInputs()),
                toJson(taskCard.collectedSlots()),
                taskCard.lastAgentMessage(),
                taskCard.confirmationQuestion(),
                taskCard.expiresAt(),
                taskCard.createdAt(),
                taskCard.updatedAt(),
                toJson(taskCard.metadata())
        );
        return taskCard;
    }

    private TaskCard toDomain(TaskCardRow row) {
        return new TaskCard(
                row.getTaskId(),
                row.getTenantId(),
                row.getUserId(),
                row.getChatSessionId(),
                row.getBindingId(),
                row.getTaskGoal(),
                row.getTaskDomain(),
                row.getAgentCode(),
                row.getAgentSessionId(),
                TaskStatus.from(row.getTaskStatus(), TaskStatus.ACTIVE),
                TaskStatus.from(row.getRawNormalizedStatus(), TaskStatus.ACTIVE),
                fromJson(row.getRequiredInputsJson(), REQUIRED_INPUTS_TYPE, List.of()),
                fromJson(row.getCollectedSlotsJson(), MAP_TYPE, Map.of()),
                row.getLastAgentMessage(),
                row.getConfirmationQuestion(),
                row.getExpiresAt(),
                row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt(),
                row.getUpdatedAt() == null ? Instant.EPOCH : row.getUpdatedAt(),
                fromJson(row.getMetadataJson(), MAP_TYPE, Map.of())
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("TaskCard JSON 序列化失败", ex);
        }
    }

    private <T> T fromJson(String value, TypeReference<T> type, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }
}
