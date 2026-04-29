package com.huawei.finance.front.one.infrastructure.agent.binding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.gateway.AgentBindingRepository;
import com.huawei.finance.front.one.domain.agent.AgentBinding;
import com.huawei.finance.front.one.domain.agent.AgentBindingStatus;
import com.huawei.finance.front.one.domain.agent.AgentBindingType;
import com.huawei.finance.front.one.infrastructure.agent.binding.mybatis.AgentBindingMapper;
import com.huawei.finance.front.one.infrastructure.agent.binding.mybatis.AgentBindingRow;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class OpenGaussAgentBindingRepository implements AgentBindingRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AgentBindingMapper mapper;
    private final ObjectMapper objectMapper;

    public OpenGaussAgentBindingRepository(AgentBindingMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AgentBinding> findActive(String tenantId, String userId, String sessionId) {
        // 只返回仍可续接的 binding。过期和终态 binding 留在事实源中用于审计/排障，
        // 但不会参与新一轮路由。
        return Optional.ofNullable(mapper.findActive(tenantId, userId, sessionId)).map(this::toDomain);
    }

    @Override
    public AgentBinding save(AgentBinding binding) {
        // openGauss 是 AgentBinding 的最终事实源；Redis 只是热缓存。
        // 因此所有状态变化都先通过 repository 落库，再由 application service 决定是否刷新 Redis。
        mapper.upsert(
                binding.id(),
                binding.tenantId(),
                binding.userId(),
                binding.chatSessionId(),
                binding.bindingType().name(),
                binding.agentCode(),
                binding.provider(),
                binding.agentSessionId(),
                binding.runtimeSessionId(),
                binding.status().name(),
                binding.lastRunId(),
                binding.expiresAt(),
                toJson(binding.metadata()),
                binding.createdAt(),
                binding.updatedAt()
        );
        return binding;
    }

    private AgentBinding toDomain(AgentBindingRow row) {
        // MyBatis row 与 domain record 分开，避免基础设施层字段形态泄漏到 application/domain。
        return new AgentBinding(
                row.getId(),
                row.getTenantId(),
                row.getUserId(),
                row.getChatSessionId(),
                AgentBindingType.valueOf(row.getBindingType()),
                row.getAgentCode(),
                row.getProvider(),
                row.getAgentSessionId(),
                row.getRuntimeSessionId(),
                AgentBindingStatus.valueOf(row.getStatus()),
                row.getLastRunId(),
                row.getExpiresAt(),
                row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt(),
                row.getUpdatedAt() == null ? Instant.EPOCH : row.getUpdatedAt(),
                fromJson(row.getMetadataJson())
        );
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("AgentBinding metadata 序列化失败", ex);
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
