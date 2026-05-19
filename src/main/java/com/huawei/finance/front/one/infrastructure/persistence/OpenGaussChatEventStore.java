package com.huawei.finance.front.one.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.StoredChatEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 聊天事件 openGauss 事实源。
 *
 * <p>事件流会被 WebSocket 实时消费，并通过 SSE 在断线重连、刷新页面、审计和排障时回放。
 * 因此这里不再使用 JVM 内存列表，而是把每个 ChatEvent 持久化到 fin_ex_chat_event_t。</p>
 */
@Repository
public class OpenGaussChatEventStore implements ChatEventStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ChatEventMapper mapper;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;

    public OpenGaussChatEventStore(ChatEventMapper mapper, ObjectMapper objectMapper, IdGenerator idGenerator) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public ChatEvent append(ChatEvent event) {
        String eventId = idGenerator.newId("event",
                IdGenerateContext.of(null, null, event.sessionId(), event.runId()));
        Instant createdAt = event.createdAt() == null ? Instant.now() : event.createdAt();
        Long seq = mapper.nextSeq();
        if (seq == null) {
            throw new IllegalStateException("聊天事件序号生成失败");
        }
        // seq 是前端恢复游标，必须由 openGauss sequence 生成，避免多实例本地生成导致 afterSeq 歧义。
        int inserted = mapper.insertFromSession(
                eventId,
                event.sessionId(),
                event.runId(),
                seq,
                event.type(),
                toJson(event.payload()),
                createdAt
        );
        if (inserted == 0) {
            throw new IllegalStateException("聊天事件无法落库，关联会话不存在: " + event.sessionId());
        }
        ChatEventRow insertedRow = mapper.findById(eventId);
        if (insertedRow == null) {
            throw new IllegalStateException("聊天事件落库后回读失败: " + eventId);
        }
        return toDomain(insertedRow);
    }

    @Override
    public List<ChatEvent> findBySessionIdAndAfterSeq(String sessionId, long afterSeq) {
        return mapper.findBySessionIdAndAfterSeq(sessionId, afterSeq).stream().map(this::toDomain).toList();
    }

    @Override
    public List<ChatEvent> findByRunIdAndAfterSeq(String runId, long afterSeq) {
        return mapper.findByRunIdAndAfterSeq(runId, afterSeq).stream().map(this::toDomain).toList();
    }

    @Override
    public long findLatestSeqBySessionId(String sessionId) {
        return mapper.findLatestSeqBySessionId(sessionId);
    }

    private ChatEvent toDomain(ChatEventRow row) {
        return new StoredChatEvent(
                row.getRunId(),
                row.getSessionId(),
                row.getSeq(),
                row.getEventType(),
                row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt(),
                fromJson(row.getPayloadJson())
        );
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("聊天事件 payload 序列化失败", ex);
        }
    }

    private Map<String, Object> fromJson(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of("raw", payloadJson);
        }
    }
}
