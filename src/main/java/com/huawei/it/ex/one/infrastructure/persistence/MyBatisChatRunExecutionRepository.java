package com.huawei.it.ex.one.infrastructure.persistence;

import com.huawei.it.ex.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ChatRun 执行控制面的数据库事实源实现。
 */
@Repository
public class MyBatisChatRunExecutionRepository implements ChatRunExecutionRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ChatRunExecutionMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisChatRunExecutionRepository(ChatRunExecutionMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatRunExecution createForRun(ChatRun run, String executionId, String ownerInstanceId, Duration leaseDuration) {
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(normalizeLease(leaseDuration));
        try {
            mapper.insert(new ChatRunExecutionWriteRow(
                    executionId,
                    run.id(),
                    run.tenantId(),
                    run.userId(),
                    run.sessionId(),
                    ChatRunExecutionStatus.RUNNING.name(),
                    ownerInstanceId,
                    now,
                    leaseUntil,
                    1L,
                    null,
                    null,
                    0,
                    null,
                    null,
                    toJson(Map.of()),
                    now,
                    now
            ));
        } catch (DuplicateKeyException ignored) {
            // createRunning 已经通过 active run 互斥保护。这里幂等回读，避免重试时因为已有 execution 误报。
        }
        return findByRunId(run.id())
                .orElseThrow(() -> new IllegalStateException("run execution 创建后回读失败: " + run.id()));
    }

    @Override
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public ChatRunExecution createForInteractionRun(ChatRun run, String executionId, String ownerInstanceId,
                                                     Duration leaseDuration, String interactionId) {
        if (mapper.claimInteractionExecutionInitialization(run.id(), interactionId) != 1) {
            throw new IllegalStateException("Interaction continuation execution 初始化被拒绝: runId=" + run.id());
        }
        return createForRun(run, executionId, ownerInstanceId, leaseDuration);
    }

    @Override
    public Optional<ChatRunExecution> findByRunId(String runId) {
        return Optional.ofNullable(mapper.findByRunId(runId)).map(this::toDomain);
    }

    @Override
    @Transactional(
            readOnly = true,
            timeoutString = "${financeex.chat-run.execution-owner-query-timeout-seconds:2}")
    public boolean isCurrentOwnerRunning(RunExecutionClaim claim) {
        return claim != null && mapper.countCurrentOwnerRunning(
                claim.runId(), claim.ownerInstanceId(), claim.fencingToken()) == 1;
    }

    @Override
    public boolean heartbeat(String runId, String ownerInstanceId, long fencingToken, Duration leaseDuration) {
        Instant leaseUntil = Instant.now().plus(normalizeLease(leaseDuration));
        return mapper.heartbeat(runId, ownerInstanceId, fencingToken, leaseUntil) > 0;
    }

    @Override
    @Transactional(timeoutString = "${financeex.chat-run.heartbeat-transaction-timeout-seconds:2}")
    public List<RunExecutionClaim> heartbeatBatch(List<RunExecutionClaim> claims, Duration leaseDuration) {
        if (claims == null || claims.isEmpty()) {
            return List.of();
        }
        List<RunExecutionClaim> batch = List.copyOf(claims);
        Instant leaseUntil = Instant.now().plus(normalizeLease(leaseDuration));
        if (mapper.heartbeatBatch(batch, leaseUntil) == batch.size()) {
            return batch;
        }
        // UPDATE 未全部命中时在同一事务内回读，避免仅凭影响行数误取消仍有效的执行流。
        return mapper.findHeartbeatEligibleClaims(batch).stream()
                .map(row -> new RunExecutionClaim(
                        row.getRunId(),
                        row.getOwnerInstanceId(),
                        row.getFencingToken() == null ? 1L : row.getFencingToken()))
                .toList();
    }

    @Override
    public boolean markTerminal(String runId, ChatRunExecutionStatus terminalStatus) {
        if (terminalStatus == null || !terminalStatus.terminal()) {
            return false;
        }
        return mapper.markTerminal(runId, terminalStatus.name()) > 0;
    }

    @Override
    public List<ChatRunExecution> findLeaseExpired(int limit) {
        return mapper.findLeaseExpired(Math.max(1, limit)).stream().map(this::toDomain).toList();
    }

    @Override
    public List<ChatRunExecution> findRecoveryExpired(int limit) {
        return mapper.findRecoveryExpired(Math.max(1, limit)).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<ChatRunExecution> tryClaimRecovering(String runId, String recoveredByInstanceId,
                                                        String strategy, Duration recoveryLeaseDuration) {
        Instant recoveryLeaseUntil = Instant.now().plus(normalizeLease(recoveryLeaseDuration));
        int updated = mapper.tryClaimRecovering(runId, recoveredByInstanceId, strategy, recoveryLeaseUntil);
        return updated > 0 ? findByRunId(runId) : Optional.empty();
    }

    @Override
    public Optional<ChatRunExecution> markTakeoverRunning(String runId, String ownerInstanceId, Duration leaseDuration) {
        Instant leaseUntil = Instant.now().plus(normalizeLease(leaseDuration));
        int updated = mapper.markTakeoverRunning(runId, ownerInstanceId, leaseUntil);
        return updated > 0 ? findByRunId(runId) : Optional.empty();
    }

    @Override
    public boolean isLeaseExpired(String runId, Instant now) {
        return mapper.countLeaseExpired(runId) > 0;
    }

    private ChatRunExecution toDomain(ChatRunExecutionRow row) {
        return new ChatRunExecution(
                row.getId(),
                row.getRunId(),
                row.getTenantId(),
                row.getUserId(),
                row.getSessionId(),
                ChatRunExecutionStatus.valueOf(row.getExecutionStatus()),
                row.getOwnerInstanceId(),
                row.getHeartbeatAt(),
                row.getLeaseUntil(),
                row.getFencingToken() == null ? 1L : row.getFencingToken(),
                row.getRecoveryStrategy(),
                row.getRecoveredByInstanceId(),
                row.getRecoveryAttempts() == null ? 0 : row.getRecoveryAttempts(),
                row.getRecoveryLeaseUntil(),
                row.getRuntimeResumeToken(),
                fromJson(row.getMetadataJson()),
                row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt(),
                row.getUpdatedAt() == null ? Instant.EPOCH : row.getUpdatedAt()
        );
    }

    private Duration normalizeLease(Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative() ? Duration.ofSeconds(90) : duration;
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("ChatRunExecution metadata 序列化失败", ex);
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
