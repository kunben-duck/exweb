package com.huawei.finance.front.one.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.conversation.ChatInteractionRequestRepository;
import com.huawei.finance.front.one.domain.chat.ChatInteractionRequest;
import com.huawei.finance.front.one.domain.chat.ChatInteractionStatus;
import com.huawei.finance.front.one.domain.chat.ChatInteractionType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的 Interaction 等待请求仓储。
 */
@Repository
public class MyBatisChatInteractionRequestRepository implements ChatInteractionRequestRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ChatInteractionRequestMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisChatInteractionRequestRepository(ChatInteractionRequestMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatInteractionRequest insert(ChatInteractionRequest request) {
        mapper.insert(toRow(request));
        return request;
    }

    @Override
    public Optional<ChatInteractionRequest> findByOwnerAndId(String tenantId, String userId, String interactionId) {
        if (blank(tenantId) || blank(userId) || blank(interactionId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findByOwnerAndId(tenantId, userId, interactionId))
                .map(this::toDomain);
    }

    @Override
    public Optional<ChatInteractionRequest> findWaitingBySession(String tenantId, String userId, String sessionId) {
        if (blank(tenantId) || blank(userId) || blank(sessionId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findWaitingBySession(tenantId, userId, sessionId))
                .map(this::toDomain);
    }

    @Override
    public boolean claimInteractionResponse(ChatInteractionClaimCommand command) {
        if (command == null) {
            return false;
        }
        ChatInteractionClaimRow row = new ChatInteractionClaimRow();
        row.setTenantId(command.tenantId());
        row.setUserId(command.userId());
        row.setInteractionId(command.interactionId());
        row.setContinueRunId(command.continueRunId());
        row.setResponsePayloadJson(toJson(command.responsePayload()));
        row.setNow(command.now() == null ? Instant.now() : command.now());
        return mapper.claimInteractionResponse(row) == 1;
    }

    @Override
    public int markAnswered(String tenantId, String userId, String interactionId, Instant answeredAt) {
        return mapper.markAnswered(tenantId, userId, interactionId, answeredAt == null ? Instant.now() : answeredAt);
    }

    @Override
    public int markAnsweredForRun(String tenantId, String userId, String interactionId,
                                  String continueRunId, Instant answeredAt) {
        if (blank(continueRunId)) {
            return 0;
        }
        return mapper.markAnsweredForRun(tenantId, userId, interactionId, continueRunId,
                answeredAt == null ? Instant.now() : answeredAt);
    }

    @Override
    public int markWaiting(String tenantId, String userId, String interactionId) {
        return mapper.markWaiting(tenantId, userId, interactionId);
    }

    @Override
    public int markWaitingForRun(String tenantId, String userId, String interactionId, String continueRunId) {
        if (blank(continueRunId)) {
            return 0;
        }
        return mapper.markWaitingForRun(tenantId, userId, interactionId, continueRunId);
    }

    @Override
    public List<ChatInteractionRequest> findRespondingWithTerminalContinuation(int limit) {
        return mapper.findRespondingWithTerminalContinuation(Math.max(1, limit)).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<ContinuationReconcileCandidate> findRespondingReconcileCandidates(Instant orphanBefore, int limit) {
        Instant cutoff = orphanBefore == null ? Instant.now() : orphanBefore;
        return mapper.findRespondingReconcileCandidates(cutoff, Math.max(1, limit)).stream()
                .map(row -> new ContinuationReconcileCandidate(
                        toDomain(row),
                        ContinuationReconcileState.valueOf(row.getReconcileState()),
                        cutoff))
                .toList();
    }

    @Override
    public int markWaitingIfContinuationOrphaned(String tenantId, String userId, String interactionId,
                                                  String continueRunId, Instant orphanBefore) {
        if (blank(continueRunId) || orphanBefore == null) {
            return 0;
        }
        return mapper.markWaitingIfContinuationOrphaned(
                tenantId, userId, interactionId, continueRunId, orphanBefore);
    }

    @Override
    public int cancelOpenBySession(String tenantId, String userId, String sessionId, Instant cancelledAt) {
        return mapper.cancelOpenBySession(tenantId, userId, sessionId, cancelledAt == null ? Instant.now() : cancelledAt);
    }

    @Override
    public int cancelWaitingById(String tenantId, String userId, String interactionId, Instant cancelledAt) {
        return mapper.cancelWaitingById(
                tenantId, userId, interactionId, cancelledAt == null ? Instant.now() : cancelledAt);
    }

    @Override
    public int markExpired(String tenantId, String userId, String interactionId) {
        return mapper.markExpired(tenantId, userId, interactionId);
    }

    private ChatInteractionRequestRow toRow(ChatInteractionRequest request) {
        ChatInteractionRequestRow row = new ChatInteractionRequestRow();
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
        row.setInteractionType(request.interactionType().name());
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

    private ChatInteractionRequest toDomain(ChatInteractionRequestRow row) {
        return new ChatInteractionRequest(
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
                parseInteractionType(row.getInteractionType()),
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

    private ChatInteractionType parseInteractionType(String value) {
        if (value == null || value.isBlank()) {
            return ChatInteractionType.AGENT_CLARIFICATION;
        }
        if ("DOMAIN_AGENT_SWITCH_CONFIRMATION".equals(value)) {
            return ChatInteractionType.ROUTE_SWITCH_CONFIRMATION;
        }
        return ChatInteractionType.valueOf(value);
    }

    private ChatInteractionStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return ChatInteractionStatus.WAITING;
        }
        return ChatInteractionStatus.valueOf(value);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("序列化 Interaction payload 失败", ex);
        }
    }

    private Map<String, Object> fromJson(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("反序列化 Interaction payload 失败", ex);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
