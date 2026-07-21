package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.chat.application.repository.ChatInteractionRequestRepository;
import com.huawei.it.ex.one.chat.application.repository.ChatRunExecutionRepository;
import com.huawei.it.ex.one.chat.application.repository.ChatRunRepository;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunExecution;
import com.huawei.it.ex.one.chat.domain.ErrorEvent;
import com.huawei.it.ex.one.chat.domain.RunCancelledEvent;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Owns watchdog reconciliation paths that close runs through the existing terminal commit service. */
@Service
public class ChatRunRecoveryReconciliationService {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunRecoveryOrchestrator.class);

    private final ChatRunExecutionRepository executionRepository;
    private final ChatRunRepository runRepository;
    private final ChatRunOperationalProperties properties;
    private final ChatInteractionApplicationService interactionService;
    private final ChatRunTerminalCommitService terminalCommitService;
    private final ChatStreamApplicationService streamService;
    private final ChatRunApplicationService runService;

    public ChatRunRecoveryReconciliationService(
            ChatRunExecutionRepository executionRepository,
            ChatRunRepository runRepository,
            ChatRunOperationalProperties properties,
            ChatInteractionApplicationService interactionService,
            ChatRunTerminalCommitService terminalCommitService,
            ChatStreamApplicationService streamService,
            ChatRunApplicationService runService) {
        this.executionRepository = executionRepository;
        this.runRepository = runRepository;
        this.properties = properties;
        this.interactionService = interactionService;
        this.terminalCommitService = terminalCommitService;
        this.streamService = streamService;
        this.runService = runService;
    }

    public boolean terminalCommitAvailable() {
        return terminalCommitService != null && streamService != null && runService != null;
    }

    public int reconcileRunExecutionInitOrphans(int limit) {
        if (!terminalCommitAvailable() || limit <= 0) {
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

    public void reconcileTerminalInteractionClaims(int batchSize) {
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

    public boolean recoverCancellingRun(ChatRun run, ChatRunExecution candidate, String instanceId) {
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

    private int reconcileMissingExecution(
            ChatInteractionRequestRepository.ContinuationReconcileCandidate candidate) {
        if (!terminalCommitAvailable() || candidate == null || candidate.request() == null) {
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
}
