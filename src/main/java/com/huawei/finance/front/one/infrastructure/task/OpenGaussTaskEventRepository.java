package com.huawei.finance.front.one.infrastructure.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.task.TaskEventRepository;
import com.huawei.finance.front.one.domain.task.TaskEvent;
import com.huawei.finance.front.one.infrastructure.task.mybatis.TaskEventMapper;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * TaskEvent 的 openGauss 持久化实现。
 *
 * <p>事件表只追加不覆盖，用于审计任务状态迁移和排查 SubAgent 响应标准化结果。</p>
 */
@Repository
public class OpenGaussTaskEventRepository implements TaskEventRepository {
    private final TaskEventMapper mapper;
    private final ObjectMapper objectMapper;

    public OpenGaussTaskEventRepository(TaskEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(TaskEvent event) {
        mapper.insert(
                event.id(),
                event.tenantId(),
                event.userId(),
                event.chatSessionId(),
                event.taskId(),
                event.runId(),
                event.eventType(),
                event.fromStatus() == null ? null : event.fromStatus().name(),
                event.toStatus() == null ? null : event.toStatus().name(),
                toJson(event.payload()),
                event.createdAt()
        );
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("TaskEvent payload 序列化失败", ex);
        }
    }
}
