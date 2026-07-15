package com.huawei.it.ex.one.infrastructure.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.application.integration.memory.ChatFeedbackRepository;
import com.huawei.it.ex.one.domain.chat.ChatMessageFeedback;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * 消息反馈数据库事实源实现。
 */
@Repository
public class MyBatisChatFeedbackRepository implements ChatFeedbackRepository {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final ChatFeedbackMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisChatFeedbackRepository(ChatFeedbackMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatMessageFeedback save(ChatMessageFeedback feedback) {
        ChatMessageFeedback current = findByMessage(feedback.tenantId(), feedback.userId(), feedback.messageId())
                .map(existing -> withCurrentState(existing, feedback))
                .orElse(feedback);
        int updated = mapper.update(toRow(current, STATUS_ACTIVE));
        if (updated == 0) {
            return insertOrUpdateAfterRace(current);
        }
        return current;
    }

    @Override
    public Optional<ChatMessageFeedback> cancel(String tenantId, String userId, String messageId, Instant cancelledAt) {
        int updated = mapper.cancelCurrent(tenantId, userId, messageId, cancelledAt);
        if (updated == 0) {
            return Optional.empty();
        }
        return findByMessage(tenantId, userId, messageId)
                .map(feedback -> new ChatMessageFeedback(
                        feedback.id(),
                        feedback.tenantId(),
                        feedback.userId(),
                        feedback.sessionId(),
                        feedback.messageId(),
                        feedback.runId(),
                        feedback.rating(),
                        STATUS_CANCELLED,
                        feedback.reasonCode(),
                        feedback.commentText(),
                        feedback.metadata(),
                        feedback.createdAt(),
                        cancelledAt
                ));
    }

    @Override
    public Map<String, ChatMessageFeedback> findActiveByMessages(
            String tenantId, String userId, String sessionId, Collection<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        List<String> uniqueIds = messageIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (uniqueIds.isEmpty()) {
            return Map.of();
        }
        return mapper.findActiveByMessages(tenantId, userId, sessionId, uniqueIds)
                .stream()
                .map(this::toDomain)
                .collect(Collectors.toMap(ChatMessageFeedback::messageId, feedback -> feedback, (left, right) -> right));
    }

    @Override
    public Optional<ChatMessageFeedback> findByMessage(String tenantId, String userId, String messageId) {
        return mapper.findByMessage(tenantId, userId, messageId).map(this::toDomain);
    }

    private ChatMessageFeedback withCurrentState(ChatMessageFeedback existing, ChatMessageFeedback incoming) {
        // 同一用户同一消息只保留一条当前反馈；再次点赞/点踩表示修改当前状态，而不是新增流水。
        return new ChatMessageFeedback(
                existing.id(),
                incoming.tenantId(),
                incoming.userId(),
                incoming.sessionId(),
                incoming.messageId(),
                incoming.runId(),
                incoming.rating(),
                STATUS_ACTIVE,
                incoming.reasonCode(),
                incoming.commentText(),
                incoming.metadata(),
                existing.createdAt(),
                incoming.updatedAt()
        );
    }

    private ChatMessageFeedback insertOrUpdateAfterRace(ChatMessageFeedback feedback) {
        try {
            mapper.insert(toRow(feedback, STATUS_ACTIVE));
            return feedback;
        } catch (DuplicateKeyException ex) {
            return findByMessage(feedback.tenantId(), feedback.userId(), feedback.messageId())
                    .map(existing -> withCurrentState(existing, feedback))
                    .map(existing -> {
                        mapper.update(toRow(existing, STATUS_ACTIVE));
                        return existing;
                    })
                    .orElse(feedback);
        }
    }

    private ChatMessageFeedbackRow toRow(ChatMessageFeedback feedback, String status) {
        ChatMessageFeedbackRow row = new ChatMessageFeedbackRow();
        row.setId(feedback.id());
        row.setTenantId(feedback.tenantId());
        row.setUserId(feedback.userId());
        row.setSessionId(feedback.sessionId());
        row.setMessageId(feedback.messageId());
        row.setRunId(feedback.runId());
        row.setRating(feedback.rating());
        row.setStatus(status);
        row.setReasonCode(feedback.reasonCode());
        row.setCommentText(feedback.commentText());
        row.setMetadataJson(toJson(feedback.metadata()));
        row.setCreatedAt(feedback.createdAt());
        row.setUpdatedAt(feedback.updatedAt());
        return row;
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("消息反馈 metadata 序列化失败", ex);
        }
    }

    private Map<String, Object> fromJson(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("消息反馈 metadata 反序列化失败", ex);
        }
    }

    private ChatMessageFeedback toDomain(ChatMessageFeedbackRow row) {
        return new ChatMessageFeedback(
                row.getId(),
                row.getTenantId(),
                row.getUserId(),
                row.getSessionId(),
                row.getMessageId(),
                row.getRunId(),
                row.getRating(),
                row.getStatus(),
                row.getReasonCode(),
                row.getCommentText(),
                fromJson(row.getMetadataJson()),
                row.getCreatedAt(),
                row.getUpdatedAt()
        );
    }
}
