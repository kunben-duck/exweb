package com.huawei.finance.front.one.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.StoredChatEvent;
import com.huawei.finance.front.one.infrastructure.persistence.mybatis.ChatEventMapper;
import com.huawei.finance.front.one.infrastructure.persistence.mybatis.ChatEventRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

/**
 * 聊天事件 openGauss 事实源。
 *
 * <p>事件流会被 SSE、NDJSON 和 WebSocket 实时消费，同时也要能在断线重连、审计和排障时回放。
 * 因此这里不再使用 JVM 内存列表，而是把每个 ChatEvent 持久化到 fin_ex_chat_event_t。</p>
 */
@Repository
public class OpenGaussChatEventStore implements ChatEventStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final long MILLIS_TO_SEQUENCE_FACTOR = 1_000L;

    private final ChatEventMapper mapper;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final AtomicLong sequenceGenerator = new AtomicLong(System.currentTimeMillis() * MILLIS_TO_SEQUENCE_FACTOR);

    public OpenGaussChatEventStore(ChatEventMapper mapper, ObjectMapper objectMapper, IdGenerator idGenerator) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public void append(ChatEvent event) {
        long sequence = nextPersistentSequence();
        String eventId = idGenerator.newId("event",
                IdGenerateContext.of(null, null, event.sessionId(), event.runId()));
        int inserted = mapper.insertFromSession(
                eventId,
                event.sessionId(),
                event.runId(),
                sequence,
                event.type(),
                toJson(event.payload()),
                event.createdAt() == null ? Instant.now() : event.createdAt()
        );
        if (inserted == 0) {
            throw new IllegalStateException("聊天事件无法落库，关联会话不存在: " + event.sessionId());
        }
    }

    @Override
    public List<ChatEvent> findBySessionIdAndAfterSeq(String sessionId, long afterSeq) {
        return mapper.findBySessionIdAndAfterSeq(sessionId, afterSeq).stream().map(this::toDomain).toList();
    }

    @Override
    public List<ChatEvent> findByRunId(String runId) {
        return mapper.findByRunId(runId).stream().map(this::toDomain).toList();
    }

    private long nextPersistentSequence() {
        // 领域事件的 sequence 目前由各事件工厂固定为 0；持久化层单独生成递增序号，
        // 用于 findBySessionIdAndAfterSeq 的断线续传语义，并避免同一 run 内唯一键冲突。
        long nowBasedSequence = System.currentTimeMillis() * MILLIS_TO_SEQUENCE_FACTOR;
        return sequenceGenerator.updateAndGet(previous -> Math.max(previous + 1, nowBasedSequence));
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
