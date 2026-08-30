/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatInteractionRequestRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRecoverLock;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.application.service.recovery.RuntimeTakeoverRecoveryStrategy;
import com.huawei.it.ex.one.application.service.recovery.StaleRunRecoveryContext;
import com.huawei.it.ex.one.application.service.recovery.StaleRunRecoveryResult;
import com.huawei.it.ex.one.application.service.recovery.StaleRunRecoveryStrategy;
import com.huawei.it.ex.one.application.service.recovery.StaleRunRecoveryStrategyRegistry;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;
import com.huawei.it.ex.one.domain.chat.RunCancelledEvent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * stale run 恢复编排服务。
 *
 * <p>该服务负责拉取过期 execution、执行本机容量治理、尝试 Redis 优化锁、通过数据库条件更新
 * 抢占恢复权，并按配置策略链调用具体恢复策略。它不承担定时调度，调度由 watchdog scheduler 完成。</p>
 */
@Service
public class ChatRunRecoveryOrchestrator {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunRecoveryOrchestrator.class);

    private final ChatRunExecutionRepository executionRepository;
    private final ChatRunRepository runRepository;
    private final ChatRunRecoverLock recoverLock;
    private final ApplicationInstanceIdProvider instanceIdProvider;
    private final ChatRunOperationalProperties properties;
    private final ChatRunRecoveryCapacityLimiter capacityLimiter;
    private final StaleRunRecoveryStrategyRegistry strategyRegistry;
    private final ChatInteractionApplicationService interactionService;
    private final ChatRunTerminalCommitService terminalCommitService;
    private final ChatStreamApplicationService streamService;
    private final ChatRunApplicationService runService;

    @Autowired
    public ChatRunRecoveryOrchestrator(ChatRunExecutionRepository executionRepository,
                                       ChatRunRepository runRepository,
                                       ChatRunRecoverLock recoverLock,
                                       ApplicationInstanceIdProvider instanceIdProvider,
                                       ChatRunOperationalProperties properties,
                                       ChatRunRecoveryCapacityLimiter capacityLimiter,
                                       StaleRunRecoveryStrategyRegistry strategyRegistry,
                                       ChatInteractionApplicationService interactionService,
                                       ChatRunTerminalCommitService terminalCommitService,
                                       ChatStreamApplicationService streamService,
                                       ChatRunApplicationService runService) {
        this.executionRepository = executionRepository;
        this.runRepository = runRepository;
        this.recoverLock = recoverLock;
        this.instanceIdProvider = instanceIdProvider;
        this.properties = properties;
        this.capacityLimiter = capacityLimiter;
        this.strategyRegistry = strategyRegistry;
        this.interactionService = interactionService;
        this.terminalCommitService = terminalCommitService;
        this.streamService = streamService;
        this.runService = runService;
    }

    public ChatRunRecoveryOrchestrator(ChatRunExecutionRepository executionRepository,
                                       ChatRunRepository runRepository,
                                       ChatRunRecoverLock recoverLock,
                                       ApplicationInstanceIdProvider instanceIdProvider,
                                       ChatRunOperationalProperties properties,
                                       ChatRunRecoveryCapacityLimiter capacityLimiter,
                                       StaleRunRecoveryStrategyRegistry strategyRegistry,
                                       ChatInteractionApplicationService interactionService) {
        this(executionRepository, runRepository, recoverLock, instanceIdProvider, properties,
                capacityLimiter, strategyRegistry, interactionService, null, null, null);
    }

    public ChatRunRecoveryOrchestrator(ChatRunExecutionRepository executionRepository,
                                       ChatRunRepository runRepository,
                                       ChatRunRecoverLock recoverLock,
                                       ApplicationInstanceIdProvider instanceIdProvider,
                                       ChatRunOperationalProperties properties,
                                       ChatRunRecoveryCapacityLimiter capacityLimiter,
                                       StaleRunRecoveryStrategyRegistry strategyRegistry) {
        this(executionRepository, runRepository, recoverLock, instanceIdProvider, properties,
                capacityLimiter, strategyRegistry, null, null, null, null);
    }

    /**
     * 扫描并恢复一批过期 run。
     *
     * @return 本轮成功恢复的 run 数量。
     */
    public int recoverExpiredRuns() {
        int batchSize = properties.normalizedWatchdogBatchSize();
        reconcileTerminalInteractionClaims(batchSize);
        int maxClaims = properties.normalizedWatchdogMaxClaimsPerScan();
        int recovered = reconcileRunExecutionInitOrphans(Math.min(batchSize, maxClaims));
        int remainingClaims = Math.max(0, maxClaims - recovered);
        if (remainingClaims == 0) {
            return recovered;
        }
        int asyncExpired = expireAsyncWaitingTasks(batchSize, remainingClaims);
        recovered += asyncExpired;
        remainingClaims = Math.max(0, remainingClaims - asyncExpired);
        if (remainingClaims == 0) {
            return recovered;
        }
        List<ChatRunExecution> candidates = new ArrayList<>(executionRepository.findLeaseExpired(batchSize));
        candidates.addAll(executionRepository.findRecoveryExpired(Math.max(1, batchSize - candidates.size())));
        return recovered + recoverCandidates(candidates, remainingClaims);
    }

    private int expireAsyncWaitingTasks(int batchSize, int maxClaims) {
        if (terminalCommitService == null || streamService == null || runService == null || maxClaims <= 0) {
            return 0;
        }
        int expired = 0;
        for (ChatRunExecution execution : executionRepository.findAsyncWaitingExpired(batchSize)) {
            if (expired >= maxClaims) {
                break;
            }
            try {
                if (expireAsyncWaitingTask(execution)) {
                    expired++;
                }
            } catch (RuntimeException ex) {
                log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_TRANSACTION_FAILED,
                                "DomainAgent async task timeout finalization failed")
                        .runId(execution == null ? null : execution.runId())
                        .operation("chat-run.watchdog.domain-agent-async-timeout")
                        .build(), ex);
            }
        }
        return expired;
    }

    private boolean expireAsyncWaitingTask(ChatRunExecution execution) {
        ChatRun run = runRepository.findById(execution.runId()).orElse(null);
        if (run == null || run.status().terminal()) {
            executionRepository.markTerminal(execution.runId(), statusFromRun(run));
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "DOMAIN_AGENT_ASYNC_TIMEOUT");
        payload.put("message", "DomainAgent后台任务等待超时");
        payload.put("source", "chat-run-watchdog");
        payload.put("asyncTask", true);
        payload.put("messageReady", run.assistantMessageId() != null);
        if (run.assistantMessageId() != null) {
            payload.put("assistantMessageId", run.assistantMessageId());
            payload.put("feedbackTargetMessageId", run.assistantMessageId());
        }
        ChatEvent event = ErrorEvent.of(
                run.id(),
                run.sessionId(),
                "DOMAIN_AGENT_ASYNC_TIMEOUT",
                "DomainAgent后台任务等待超时",
                payload);
        ChatRunTerminalCommitService.ExternalTerminalCommitResult result =
                terminalCommitService.commitExternalTerminal(
                        ChatRunTerminalCommitService.ExternalTerminalCommitCommand.asyncTimeout(event, run));
        if (!result.committed()) {
            return result.run() != null && result.run().status().terminal();
        }
        runService.synchronizeCommittedRunCache(result.run());
        publishTerminalBestEffort(result.event());
        return true;
    }

    private int reconcileRunExecutionInitOrphans(int limit) {
        if (terminalCommitService == null || streamService == null || runService == null || limit <= 0) {
            return 0;
        }
        Instant orphanBefore = Instant.now().minus(properties.normalizedExecutionInitOrphanGrace());
        int recovered = 0;
        for (ChatRun run : runRepository.findExecutionInitOrphans(orphanBefore, limit)) {
            try {
                ChatEvent event = ErrorEvent.of(
                        run.id(),
                        run.sessionId(),
                        "RUN_EXECUTION_INIT_ORPHANED",
                        "run execution 初始化中断，本轮已失败",
                        Map.of(
                                "code", "RUN_EXECUTION_INIT_ORPHANED",
                                "message", "run execution 初始化中断，本轮已失败",
                                "source", "chat-run-watchdog"
                        )
                );
                ChatRunTerminalCommitService.ExternalTerminalCommitResult result =
                        terminalCommitService.commitExternalTerminal(
                                ChatRunTerminalCommitService.ExternalTerminalCommitCommand.orphanRunInitialization(
                                        event, run, orphanBefore));
                if (!result.committed()) {
                    continue;
                }
                runService.synchronizeCommittedRunCache(result.run());
                publishTerminalBestEffort(result.event());
                recovered++;
            } catch (RuntimeException ex) {
                log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_TRANSACTION_FAILED,
                                "Run execution initialization orphan reconciliation failed")
                        .runId(run.id())
                        .operation("run-recovery.execution-init-orphan")
                        .build(), ex);
            }
        }
        return recovered;
    }

    private void reconcileTerminalInteractionClaims(int batchSize) {
        if (interactionService == null) {
            return;
        }
        int released = 0;
        for (ChatInteractionRequestRepository.ContinuationReconcileCandidate candidate
                : interactionService.findContinuationReconcileCandidates(batchSize)) {
            if (candidate.state() == ChatInteractionRequestRepository.ContinuationReconcileState.MISSING_EXECUTION) {
                released += reconcileMissingExecution(candidate);
            } else {
                released += interactionService.releaseContinuationReconcileCandidate(candidate);
            }
        }
        if (released > 0) {
            log.info("Reconciled orphan Interaction continuation claims. released={}", released);
        }
    }

    private int reconcileMissingExecution(
            ChatInteractionRequestRepository.ContinuationReconcileCandidate candidate) {
        if (terminalCommitService == null || streamService == null || runService == null
                || candidate == null || candidate.request() == null) {
            return 0;
        }
        String runId = candidate.request().continueRunId();
        Optional<ChatRun> current = runRepository.findById(runId);
        if (current.isEmpty() || executionRepository.findByRunId(runId).isPresent()) {
            return 0;
        }
        ChatRun run = current.get();
        ChatEvent event = ErrorEvent.of(
                run.id(),
                run.sessionId(),
                "RUN_EXECUTION_INIT_ORPHANED",
                "Interaction 续接执行控制面初始化中断，本轮已失败",
                Map.of(
                        "code", "RUN_EXECUTION_INIT_ORPHANED",
                        "message", "Interaction 续接执行控制面初始化中断，本轮已失败",
                        "source", "chat-run-watchdog",
                        "interactionId", candidate.request().id()
                )
        );
        ChatRunTerminalCommitService.ExternalTerminalCommitResult result =
                terminalCommitService.commitExternalTerminal(
                        ChatRunTerminalCommitService.ExternalTerminalCommitCommand.orphanInteraction(
                                event, run, candidate.request().id(), candidate.orphanBefore()));
        if (!result.committed()) {
            return 0;
        }
        runService.synchronizeCommittedRunCache(result.run());
        publishTerminalBestEffort(result.event());
        return 1;
    }

    private void publishTerminalBestEffort(ChatEvent event) {
        try {
            streamService.publishPersisted(event);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.WEBSOCKET_SEND_FAILED,
                            "Recovered terminal event was committed but realtime publication failed")
                    .runId(event == null ? null : event.runId())
                    .operation("run-recovery.terminal.publish")
                    .build());
        }
    }

    /**
     * 对指定 run 执行一次轻量懒收敛。
     *
     * @param runId run 标识。
     * @return true 表示本次已成功恢复或已无须恢复。
     */
    public boolean recoverExpiredRun(String runId) {
        Optional<ChatRunExecution> execution = executionRepository.findByRunId(runId);
        if (execution.isEmpty() || !executionRepository.isLeaseExpired(runId, java.time.Instant.now())) {
            return true;
        }
        if (execution.get().executionStatus() == ChatRunExecutionStatus.ASYNC_WAITING) {
            return expireAsyncWaitingTask(execution.get());
        }
        return recoverCandidates(List.of(execution.get()), 1) > 0;
    }

    private int recoverCandidates(List<ChatRunExecution> candidates, int maxClaims) {
        if (candidates == null || candidates.isEmpty() || maxClaims <= 0) {
            return 0;
        }
        Map<String, Integer> tenantClaims = new LinkedHashMap<>();
        int recovered = 0;
        for (ChatRunExecution execution : candidates) {
            if (recovered >= maxClaims) {
                break;
            }
            int tenantCount = tenantClaims.getOrDefault(execution.tenantId(), 0);
            if (tenantCount >= properties.normalizedRecoveryMaxClaimsPerTenantPerScan()) {
                continue;
            }
            if (recoverOne(execution)) {
                recovered++;
                tenantClaims.put(execution.tenantId(), tenantCount + 1);
            }
        }
        return recovered;
    }

    private boolean recoverOne(ChatRunExecution candidate) {
        ChatRunRecoveryCapacityLimiter.Permit recoveryPermit = capacityLimiter.tryAcquireRecovery();
        if (recoveryPermit == null) {
            return false;
        }
        try (recoveryPermit) {
            Optional<ChatRun> run = runRepository.findById(candidate.runId());
            if (run.isEmpty() || run.get().status().terminal()) {
                executionRepository.markTerminal(candidate.runId(), statusFromRun(run.orElse(null)));
                return false;
            }
            String instanceId = instanceIdProvider.currentInstanceId();
            if (properties.isRecoverLockEnabled()
                    && !recoverLock.tryLock(candidate.runId(), instanceId, properties.normalizedRecoverLockTtl())) {
                return false;
            }
            if (run.get().status() == ChatRunStatus.CANCELLING
                    && terminalCommitService != null && streamService != null && runService != null) {
                return recoverCancellingRun(run.get(), candidate, instanceId);
            }
            return recoverWithStrategyChain(run.get(), candidate, instanceId);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_TRANSACTION_FAILED,
                            "Stale run recovery failed")
                    .runId(candidate.runId())
                    .operation("run-recovery.execute")
                    .build(), ex);
            return false;
        }
    }

    private boolean recoverCancellingRun(ChatRun run, ChatRunExecution candidate, String instanceId) {
        Optional<ChatRunExecution> claimedExecution = executionRepository.tryClaimRecovering(
                run.id(), instanceId, "CANCEL_PENDING", properties.normalizedLeaseDuration());
        if (claimedExecution.isEmpty()) {
            return false;
        }
        ChatEvent event = RunCancelledEvent.of(
                run.id(), run.sessionId(), run.cancelReason(), false, null);
        ChatRunTerminalCommitService.ExternalTerminalCommitResult result =
                terminalCommitService.commitExternalTerminal(
                        ChatRunTerminalCommitService.ExternalTerminalCommitCommand.recovery(
                                event, run, claimedExecution.get(), instanceId));
        if (result.committed()) {
            runService.synchronizeCommittedRunCache(result.run());
            publishTerminalBestEffort(result.event());
            log.info("stale cancelling run closed as cancelled. runId={}, previousOwner={}",
                    run.id(), candidate.ownerInstanceId());
            return true;
        }
        if (result.run() != null && result.run().status().terminal()) {
            runService.synchronizeCommittedRunCache(result.run());
            return true;
        }
        return false;
    }

    private boolean recoverWithStrategyChain(ChatRun run, ChatRunExecution candidate, String instanceId) {
        ChatRunExecution currentExecution = candidate;
        boolean claimed = false;
        for (String configuredName : properties.normalizedStaleRecoveryStrategies()) {
            String strategyName = normalize(configuredName);
            StaleRunRecoveryStrategy strategy = strategyRegistry.find(strategyName);
            if (strategy == null) {
                continue;
            }
            StaleRunRecoveryContext preClaimContext = new StaleRunRecoveryContext(run, currentExecution, instanceId, strategyName);
            if (!strategy.supports(preClaimContext)) {
                continue;
            }
            ChatRunRecoveryCapacityLimiter.Permit takeoverPermit = null;
            if (RuntimeTakeoverRecoveryStrategy.NAME.equals(strategyName)) {
                takeoverPermit = capacityLimiter.tryAcquireTakeover();
                if (takeoverPermit == null) {
                    continue;
                }
            }
            try {
                if (!claimed) {
                    Optional<ChatRunExecution> claimedExecution = executionRepository.tryClaimRecovering(
                            run.id(),
                            instanceId,
                            strategyName,
                            properties.normalizedLeaseDuration()
                    );
                    if (claimedExecution.isEmpty()) {
                        return false;
                    }
                    currentExecution = claimedExecution.get();
                    claimed = true;
                }
                StaleRunRecoveryResult result = strategy.recover(
                        new StaleRunRecoveryContext(run, currentExecution, instanceId, strategyName));
                if (result.recovered()) {
                    log.info("stale run recovered. runId={}, strategy={}, message={}",
                            run.id(), result.strategy(), result.message());
                    return true;
                }
            } finally {
                if (takeoverPermit != null) {
                    takeoverPermit.close();
                }
            }
        }
        return false;
    }

    private String normalize(String strategyName) {
        return strategyName == null ? "" : strategyName.trim().toUpperCase(Locale.ROOT);
    }

    private com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus statusFromRun(ChatRun run) {
        if (run == null || run.status() == null || !run.status().terminal()) {
            return com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus.FAILED;
        }
        return switch (run.status()) {
            case COMPLETED -> com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus.COMPLETED;
            case WAITING_USER -> com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus.WAITING_USER;
            case CANCELLED -> com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus.CANCELLED;
            case FAILED -> com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus.FAILED;
            default -> com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus.FAILED;
        };
    }
}
