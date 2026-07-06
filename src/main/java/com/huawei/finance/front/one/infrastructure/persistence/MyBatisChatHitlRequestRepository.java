package com.huawei.finance.front.one.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.conversation.ChatHitlRequestRepository;
import com.huawei.finance.front.one.domain.chat.ChatHitlRequest;
import com.huawei.finance.front.one.domain.chat.ChatHitlStatus;
import com.huawei.finance.front.one.domain.chat.ChatHitlWaitingType;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的 HITL 等待请求仓储。
 */
@Repository
public class MyBatisChatHitlRequestRepository implements ChatHitlRequestRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ChatHitlRequestMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisChatHitlRequestRepository(ChatHitlRequestMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatHitlRequest insert(ChatHitlRequest request) {
        mapper.insert(toRow(request));
        return request;
    }

    @Override
    public Optional<ChatHitlRequest> findByOwnerAndId(String tenantId, String userId, String hitlRequestId) {
        if (blank(tenantId) || blank(userId) || blank(hitlRequestId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findByOwnerAndId(tenantId, userId, hitlRequestId))
                .map(this::toDomain);
    }

    @Override
    public Optional<ChatHitlRequest> findWaitingBySession(String tenantId, String userId, String sessionId) {
        if (blank(tenantId) || blank(userId) || blank(sessionId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findWaitingBySession(tenantId, userId, sessionId))
                .map(this::toDomain);
    }

    @Override
    public boolean claimForResponse(ChatHitlClaimCommand command) {
        if (command == null) {
            return false;
        }
        ChatHitlClaimRow row = new ChatHitlClaimRow();
        row.setTenantId(command.tenantId());
        row.setUserId(command.userId());
        row.setHitlRequestId(command.hitlRequestId());
        row.setContinueRunId(command.continueRunId());
        row.setResponsePayloadJson(toJson(command.responsePayload()));
        row.setNow(command.now() == null ? Instant.now() : command.now());
        return mapper.claimForResponse(row) == 1;
    }

    @Override
    public int markAnswered(String tenantId, String userId, String hitlRequestId, Instant answeredAt) {
        return mapper.markAnswered(tenantId, userId, hitlRequestId, answeredAt == null ? Instant.now() : answeredAt);
    }

    @Override
    public int markWaiting(String tenantId, String userId, String hitlRequestId) {
        return mapper.markWaiting(tenantId, userId, hitlRequestId);
    }

    @Override
    public int cancelOpenBySession(String tenantId, String userId, String sessionId, Instant cancelledAt) {
        return mapper.cancelOpenBySession(tenantId, userId, sessionId, cancelledAt == null ? Instant.now() : cancelledAt);
    }

    @Override
    public int markExpired(String tenantId, String userId, String hitlRequestId) {
        return mapper.markExpired(tenantId, userId, hitlRequestId);
    }

    private ChatHitlRequestRow toRow(ChatHitlRequest request) {
        ChatHitlRequestRow row = new ChatHitlRequestRow();
        row.setId(request.id());
        row.setTenantId(request.tenantId());
        row.setUserId(request.userId());
        row.setSessionId(request.sessionId());
        row.setSourceRunId(request.sourceRunId());
        row.setContinueRunId(request.continueRunId());
        row.setUserMessageId(request.userMessageId());
        row.setAssistantMessageId(request.assistantMessageId());
        row.setRuntimeProvider(request.runtimeProvider());
        row.setRuntimeBindingId(request.runtimeBindingId());
        row.setRuntimeSessionId(request.runtimeSessionId());
        row.setApprovalId(request.approvalId());
        row.setWaitingType(request.waitingType().name());
        row.setStatus(request.status().name());
        row.setRequestPayloadJson(toJson(request.requestPayload()));
        row.setResponsePayloadJson(toJson(request.responsePayload()));
        row.setExpiresAt(request.expiresAt());
        row.setAnsweredAt(request.answeredAt());
        row.setCancelledAt(request.cancelledAt());
        row.setCreatedAt(request.createdAt());
        row.setUpdatedAt(request.updatedAt());
        return row;
    }

    private ChatHitlRequest toDomain(ChatHitlRequestRow row) {
        return new ChatHitlRequest(
                row.getId(),
                row.getTenantId(),
                row.getUserId(),
                row.getSessionId(),
                row.getSourceRunId(),
                row.getContinueRunId(),
                row.getUserMessageId(),
                row.getAssistantMessageId(),
                row.getRuntimeProvider(),
                row.getRuntimeBindingId(),
                row.getRuntimeSessionId(),
                row.getApprovalId(),
                parseWaitingType(row.getWaitingType()),
                parseStatus(row.getStatus()),
                fromJson(row.getRequestPayloadJson()),
                fromJson(row.getResponsePayloadJson()),
                row.getExpiresAt(),
                row.getAnsweredAt(),
                row.getCancelledAt(),
                row.getCreatedAt(),
                row.getUpdatedAt()
        );
    }

    private ChatHitlWaitingType parseWaitingType(String value) {
        if (value == null || value.isBlank()) {
            return ChatHitlWaitingType.CLARIFICATION;
        }
        return ChatHitlWaitingType.valueOf(value);
    }

    private ChatHitlStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return ChatHitlStatus.WAITING;
        }
        return ChatHitlStatus.valueOf(value);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("序列化 HITL payload 失败", ex);
        }
    }

    private Map<String, Object> fromJson(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("反序列化 HITL payload 失败", ex);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
