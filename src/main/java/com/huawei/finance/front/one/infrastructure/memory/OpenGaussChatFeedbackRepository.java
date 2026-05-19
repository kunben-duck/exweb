package com.huawei.finance.front.one.infrastructure.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.memory.ChatFeedbackRepository;
import com.huawei.finance.front.one.domain.chat.ChatMessageFeedback;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * 消息反馈 openGauss 事实源实现。
 */
@Repository
public class OpenGaussChatFeedbackRepository implements ChatFeedbackRepository {
    private final ChatFeedbackMapper mapper;
    private final ObjectMapper objectMapper;

    public OpenGaussChatFeedbackRepository(ChatFeedbackMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatMessageFeedback save(ChatMessageFeedback feedback) {
        int updated = mapper.update(
                feedback.id(),
                feedback.tenantId(),
                feedback.userId(),
                feedback.sessionId(),
                feedback.messageId(),
                feedback.runId(),
                feedback.rating(),
                feedback.reasonCode(),
                feedback.commentText(),
                toJson(feedback.metadata()),
                feedback.createdAt(),
                feedback.updatedAt()
        );
        if (updated == 0) {
            try {
                mapper.insert(
                        feedback.id(),
                        feedback.tenantId(),
                        feedback.userId(),
                        feedback.sessionId(),
                        feedback.messageId(),
                        feedback.runId(),
                        feedback.rating(),
                        feedback.reasonCode(),
                        feedback.commentText(),
                        toJson(feedback.metadata()),
                        feedback.createdAt(),
                        feedback.updatedAt()
                );
            } catch (DuplicateKeyException ex) {
                // 反馈允许用户重复修改，避免使用 PostgreSQL 专有 upsert，重复插入时退回更新。
                mapper.update(
                        feedback.id(),
                        feedback.tenantId(),
                        feedback.userId(),
                        feedback.sessionId(),
                        feedback.messageId(),
                        feedback.runId(),
                        feedback.rating(),
                        feedback.reasonCode(),
                        feedback.commentText(),
                        toJson(feedback.metadata()),
                        feedback.createdAt(),
                        feedback.updatedAt()
                );
            }
        }
        return feedback;
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("消息反馈 metadata 序列化失败", ex);
        }
    }
}
