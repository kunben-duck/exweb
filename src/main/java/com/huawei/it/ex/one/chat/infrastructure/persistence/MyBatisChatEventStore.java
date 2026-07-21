package com.huawei.it.ex.one.chat.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.chat.application.model.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.chat.application.repository.ChatEventStore;
import com.huawei.it.ex.one.common.id.IdGenerateContext;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import com.huawei.it.ex.one.chat.domain.StoredChatEvent;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 聊天事件数据库事实源。
 *
 * <p>事件流会被 WebSocket 实时消费，并通过 Event Resume 在断线重连、刷新页面、审计和排障时回放。
 * 因此这里不再使用 JVM 内存列表，而是把每个 ChatEvent 持久化到 fin_ex_chat_event_t。</p>
 */
@Repository
public class MyBatisChatEventStore implements ChatEventStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ChatEventMapper mapper;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;

    public MyBatisChatEventStore(ChatEventMapper mapper, ObjectMapper objectMapper, IdGenerator idGenerator) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public ChatEvent append(ChatEvent event) {
        ChatEventAppendContextRow context = mapper.findEventAppendContext(event.sessionId(), event.runId());
        if (context == null) {
            throw new IllegalStateException("聊天事件无法落库，run 与 session 归属不一致或不存在: runId="
                    + event.runId() + ", sessionId=" + event.sessionId());
        }
        validateAppendContext(context, event);
        String eventId = idGenerator.newId("event",
                IdGenerateContext.of(null, null, event.sessionId(), event.runId()));
        Instant createdAt = event.createdAt() == null ? Instant.now() : event.createdAt();
        Long seq = mapper.nextSeq();
        if (seq == null) {
            throw new IllegalStateException("聊天事件序号生成失败");
        }
        // seq 是前端恢复游标，必须由数据库 sequence 生成，避免多实例本地生成导致 afterSeq 歧义。
        int inserted = mapper.insert(new ChatEventWriteRow(eventId, context.tenantId(), context.userId(),
                context.sessionId(), context.runId(), seq, event.type(), toJson(event.payload()), createdAt));
        if (inserted == 0) {
            throw new IllegalStateException("聊天事件 INSERT VALUES 未写入数据: runId="
                    + event.runId() + ", sessionId=" + event.sessionId());
        }
        return toStoredEvent(event, seq, createdAt);
    }

    @Override
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public ChatEvent appendWithExecutionGuard(ChatEvent event, RunExecutionClaim claim) {
        if (claim == null) {
            throw new ChatEventAppendRejectedException("run execution claim 为空，拒绝写入聊天事件");
        }
        ChatEventAppendContextRow context;
        try {
            context = mapper.lockRunForEventAppend(event.sessionId(), event.runId(),
                    claim.ownerInstanceId(), claim.fencingToken());
            if (context == null) {
                throw new ChatEventAppendRejectedException("聊天事件写入被 run/execution 行栅栏拒绝: runId="
                        + event.runId() + ", sessionId=" + event.sessionId());
            }
        } catch (RuntimeException ex) {
            if (lockUnavailable(ex)) {
                throw new ChatEventAppendRejectedException("聊天事件写入发现终态行锁，拒绝迟到事件: runId="
                        + event.runId() + ", sessionId=" + event.sessionId(), ex);
            }
            throw ex;
        }
        validateAppendContext(context, event);
        String eventId = idGenerator.newId("event",
                IdGenerateContext.of(null, null, event.sessionId(), event.runId()));
        Instant createdAt = event.createdAt() == null ? Instant.now() : event.createdAt();
        Long seq = mapper.nextSeq();
        if (seq == null) {
            throw new IllegalStateException("聊天事件序号生成失败");
        }
        // run/execution 共享锁持续到本事务提交，后续 VALUES 写入期间 owner/fencing 不会漂移。
        int inserted = mapper.insert(new ChatEventWriteRow(eventId, context.tenantId(), context.userId(),
                context.sessionId(), context.runId(), seq, event.type(), toJson(event.payload()), createdAt));
        if (inserted == 0) {
            throw new ChatEventAppendRejectedException("聊天事件写入被 execution guard 拒绝: runId="
                    + event.runId() + ", sessionId=" + event.sessionId());
        }
        return toStoredEvent(event, seq, createdAt);
    }

    @Override
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public List<ChatEvent> appendBatchWithExecutionGuard(List<ChatEvent> events, RunExecutionClaim claim) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        if (events.size() == 1) {
            return List.of(appendWithExecutionGuard(events.getFirst(), claim));
        }
        validateBatch(events, claim);
        ChatEvent first = events.getFirst();
        List<PreparedBatchEvent> preparedEvents = prepareBatchEvents(events);
        ChatEventAppendContextRow context = lockBatchAppendContext(first, claim);
        validateAppendContext(context, first);

        List<Long> sequences = requireBatchSequences(events.size());
        List<ChatEventWriteRow> rows = batchRows(preparedEvents, sequences, context);
        int inserted = mapper.insertBatch(rows);
        if (inserted != events.size()) {
            throw new ChatEventAppendRejectedException("聊天事件批量写入被 execution guard 拒绝: runId="
                    + first.runId() + ", sessionId=" + first.sessionId()
                    + ", expected=" + events.size() + ", actual=" + inserted);
        }
        return storedBatchEvents(events, preparedEvents, sequences);
    }

    private ChatEventAppendContextRow lockBatchAppendContext(ChatEvent first, RunExecutionClaim claim) {
        try {
            ChatEventAppendContextRow context = mapper.lockRunForEventAppend(first.sessionId(), first.runId(),
                    claim.ownerInstanceId(), claim.fencingToken());
            if (context == null) {
                throw new ChatEventAppendRejectedException("聊天事件批量写入被 run/execution 行栅栏拒绝: runId="
                        + first.runId() + ", sessionId=" + first.sessionId());
            }
            return context;
        } catch (RuntimeException ex) {
            if (lockUnavailable(ex)) {
                throw new ChatEventAppendRejectedException("聊天事件批量写入发现终态行锁，拒绝迟到事件: runId="
                        + first.runId() + ", sessionId=" + first.sessionId(), ex);
            }
            throw ex;
        }
    }

    private List<Long> requireBatchSequences(int eventCount) {
        List<Long> sequences = mapper.nextSeqs(eventCount);
        if (sequences == null || sequences.size() != eventCount
                || sequences.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalStateException("聊天事件批量序号生成失败: expected=" + eventCount
                    + ", actual=" + (sequences == null ? 0 : sequences.size()));
        }
        return sequences;
    }

    private List<ChatEventWriteRow> batchRows(List<PreparedBatchEvent> preparedEvents, List<Long> sequences,
                                               ChatEventAppendContextRow context) {
        List<ChatEventWriteRow> rows = new java.util.ArrayList<>(preparedEvents.size());
        for (int index = 0; index < preparedEvents.size(); index++) {
            PreparedBatchEvent prepared = preparedEvents.get(index);
            ChatEvent event = prepared.event();
            rows.add(new ChatEventWriteRow(
                    prepared.eventId(), context.tenantId(), context.userId(), context.sessionId(), context.runId(),
                    sequences.get(index), event.type(), prepared.payloadJson(), prepared.createdAt()));
        }
        return rows;
    }

    private List<ChatEvent> storedBatchEvents(List<ChatEvent> events, List<PreparedBatchEvent> preparedEvents,
                                              List<Long> sequences) {
        List<ChatEvent> stored = new java.util.ArrayList<>(events.size());
        for (int index = 0; index < events.size(); index++) {
            stored.add(toStoredEvent(events.get(index), sequences.get(index), preparedEvents.get(index).createdAt()));
        }
        return List.copyOf(stored);
    }

    private void validateAppendContext(ChatEventAppendContextRow context, ChatEvent event) {
        if (context.tenantId() == null || context.userId() == null
                || !event.sessionId().equals(context.sessionId())
                || !event.runId().equals(context.runId())) {
            throw new IllegalStateException("聊天事件数据库归属上下文不完整或与事件不匹配: runId="
                    + event.runId() + ", sessionId=" + event.sessionId());
        }
    }

    private List<PreparedBatchEvent> prepareBatchEvents(List<ChatEvent> events) {
        List<PreparedBatchEvent> prepared = new java.util.ArrayList<>(events.size());
        for (ChatEvent event : events) {
            Instant createdAt = event.createdAt() == null ? Instant.now() : event.createdAt();
            String eventId = idGenerator.newId("event",
                    IdGenerateContext.of(null, null, event.sessionId(), event.runId()));
            prepared.add(new PreparedBatchEvent(event, eventId, createdAt, toJson(event.payload())));
        }
        return List.copyOf(prepared);
    }

    private void validateBatch(List<ChatEvent> events, RunExecutionClaim claim) {
        if (claim == null) {
            throw new ChatEventAppendRejectedException("run execution claim 为空，拒绝批量写入聊天事件");
        }
        ChatEvent first = events.getFirst();
        if (first == null || first.runId() == null || first.sessionId() == null
                || !first.runId().equals(claim.runId())) {
            throw new ChatEventAppendRejectedException("聊天事件批次与 execution claim 不匹配");
        }
        for (ChatEvent event : events) {
            if (event == null || !first.runId().equals(event.runId())
                    || !first.sessionId().equals(event.sessionId())) {
                throw new IllegalArgumentException("聊天事件批次只能包含同一 run/session 的事件");
            }
        }
    }

    private boolean lockUnavailable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CannotAcquireLockException) {
                return true;
            }
            if (current instanceof SQLException sqlException && "55P03".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId,
                                                         String sessionId, long afterSeq) {
        return mapper.findByOwnerAndSessionAfterSeq(tenantId, userId, sessionId, afterSeq)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId,
                                                     String runId, long afterSeq) {
        return mapper.findByOwnerAndRunAfterSeq(tenantId, userId, sessionId, runId, afterSeq)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId) {
        return mapper.findLatestSeqByOwnerAndSession(tenantId, userId, sessionId);
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

    private ChatEvent toStoredEvent(ChatEvent event, long seq, Instant createdAt) {
        return new StoredChatEvent(
                event.runId(),
                event.sessionId(),
                seq,
                event.type(),
                createdAt,
                event.payload() == null ? Map.of() : event.payload()
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

    private record PreparedBatchEvent(ChatEvent event, String eventId, Instant createdAt, String payloadJson) {
    }
}
