package com.huawei.it.ex.one.infrastructure.persistence;

import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.domain.chat.ActiveRunExistsException;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ChatRun 的数据库事实源实现。
 */
@Repository
public class MyBatisChatRunRepository implements ChatRunRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String ACTIVE_RUN_UNIQUE_INDEX = "uk_fin_ex_chat_run_active_session";

    private final ChatRunMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisChatRunRepository(ChatRunMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatRun save(ChatRun run) {
        ChatRunWriteRow row = toRow(run);
        int updated = mapper.updateExisting(row);
        if (updated == 0) {
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException ex) {
                // 避免使用 具体数据库专有 upsert；并发创建同一 run 时退化为受终态保护的更新。
                mapper.updateExisting(row);
            }
        }
        return findById(run.id()).orElse(run);
    }

    @Override
    public ChatRun insert(ChatRun run) {
        try {
            mapper.insert(toRow(run));
        } catch (DuplicateKeyException ex) {
            throw translateInsertConflict(run, ex);
        }
        return findById(run.id())
                .orElseThrow(() -> new IllegalStateException("新建 run 回读失败: " + run.id()));
    }

    @Override
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public Optional<ChatRun> insertInteractionContinuationIfClaimed(ChatRun run, String interactionId) {
        if (run == null || interactionId == null || interactionId.isBlank()) {
            return Optional.empty();
        }
        Integer sessionLocked = mapper.lockSessionForInteractionContinuation(
                run.tenantId(), run.userId(), run.sessionId());
        if (sessionLocked == null || sessionLocked != 1) {
            throw new IllegalArgumentException("会话不存在或不属于当前用户: " + run.sessionId());
        }
        Integer claimLocked = mapper.lockInteractionContinuationClaim(
                interactionId, run.tenantId(), run.userId(), run.sessionId(), run.id());
        if (claimLocked == null || claimLocked != 1) {
            return Optional.empty();
        }
        try {
            mapper.insert(toRow(run));
        } catch (DuplicateKeyException ex) {
            throw translateInsertConflict(run, ex);
        }
        return findById(run.id());
    }

    private RuntimeException translateInsertConflict(ChatRun run, DuplicateKeyException exception) {
        if (causedByConstraint(exception, ACTIVE_RUN_UNIQUE_INDEX)) {
            return new ActiveRunExistsException(run.sessionId(), "unknown");
        }
        return exception;
    }

    private boolean causedByConstraint(Throwable error, String constraintName) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT)
                    .contains(constraintName.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public boolean tryFenceOwnerTerminalCommit(OwnerTerminalFence fence) {
        if (fence == null || fence.executionClaim() == null) {
            return false;
        }
        return mapper.fenceOwnerTerminalCommit(
                fence.runId(),
                fence.tenantId(),
                fence.userId(),
                fence.sessionId(),
                fence.executionClaim().ownerInstanceId(),
                fence.executionClaim().fencingToken()
        ) == 1;
    }

    @Override
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public boolean tryMarkCancelling(StopClaim claim) {
        if (claim == null) {
            return false;
        }
        return mapper.markCancelling(claim.runId(), claim.tenantId(), claim.userId(), claim.reason(),
                claim.requestedAt()) == 1;
    }

    @Override
    public boolean tryClaimExternalTerminal(ExternalTerminalClaim claim) {
        if (claim == null || claim.guard() == null || claim.terminalStatus() == null) {
            return false;
        }
        return mapper.claimExternalTerminal(new ChatRunExternalTerminalClaimRow(
                claim.runId(),
                claim.tenantId(),
                claim.userId(),
                claim.sessionId(),
                claim.terminalStatus().name(),
                claim.cancelReason(),
                claim.finishedAt(),
                claim.guard().name(),
                claim.recoveredByInstanceId(),
                claim.fencingToken(),
                claim.interactionId(),
                claim.orphanBefore()
        )) == 1;
    }

    @Override
    public ChatRun finalizeExternalTerminal(ExternalTerminalFinalize command) {
        if (command == null || command.terminalStatus() == null) {
            throw new IllegalArgumentException("外部终态回填参数不能为空");
        }
        int updated = mapper.finalizeExternalTerminal(new ChatRunExternalTerminalFinalizeRow(
                command.runId(),
                command.tenantId(),
                command.userId(),
                command.sessionId(),
                command.terminalStatus().name(),
                command.sequence(),
                command.cancelReason(),
                command.finishedAt()
        ));
        if (updated != 1) {
            throw new IllegalStateException("外部终态事件游标回填失败: runId=" + command.runId());
        }
        return findById(command.runId())
                .orElseThrow(() -> new IllegalStateException("外部终态 run 回读失败: " + command.runId()));
    }

    private ChatRunWriteRow toRow(ChatRun run) {
        return new ChatRunWriteRow(
                run.id(),
                run.tenantId(),
                run.userId(),
                run.sessionId(),
                run.status().name(),
                run.routeType(),
                run.agentCode(),
                run.runtimeProvider(),
                run.runtimeSessionId(),
                run.runMode().name(),
                run.parentMessageId(),
                run.userMessageId(),
                run.assistantMessageId(),
                run.firstSeq(),
                run.lastSeq(),
                run.cancelReason(),
                run.startedAt(),
                run.finishedAt(),
                toJson(run.metadata()),
                run.createdAt(),
                run.updatedAt()
        );
    }

    @Override
    public Optional<ChatRun> findById(String runId) {
        return Optional.ofNullable(mapper.findById(runId)).map(this::toDomain);
    }

    @Override
    public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
        return Optional.ofNullable(mapper.findByOwnerAndId(tenantId, userId, runId)).map(this::toDomain);
    }

    @Override
    public List<ChatRun> findByTenantIdAndUserIdAndIds(String tenantId, String userId, Collection<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        return mapper.findByOwnerAndIds(tenantId, userId, runIds).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) {
        return Optional.ofNullable(mapper.findActiveBySession(tenantId, userId, sessionId)).map(this::toDomain);
    }

    @Override
    public List<ChatRun> findExecutionInitOrphans(Instant orphanBefore, int limit) {
        if (orphanBefore == null || limit <= 0) {
            return List.of();
        }
        return mapper.findExecutionInitOrphans(orphanBefore, Math.max(1, limit)).stream()
                .map(this::toDomain)
                .toList();
    }

    private ChatRun toDomain(ChatRunRow row) {
        return new ChatRun(
                row.getId(),
                row.getTenantId(),
                row.getUserId(),
                row.getSessionId(),
                ChatRunStatus.valueOf(row.getStatus()),
                row.getRouteType(),
                row.getAgentCode(),
                row.getRuntimeProvider(),
                row.getRuntimeSessionId(),
                ChatRunMode.from(row.getRunMode()),
                row.getParentMessageId(),
                row.getUserMessageId(),
                row.getAssistantMessageId(),
                row.getFirstSeq(),
                row.getLastSeq(),
                row.getCancelReason(),
                row.getStartedAt(),
                row.getFinishedAt(),
                fromJson(row.getMetadataJson()),
                row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt(),
                row.getUpdatedAt() == null ? Instant.EPOCH : row.getUpdatedAt()
        );
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("ChatRun metadata 序列化失败", ex);
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
