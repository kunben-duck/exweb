package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.service.ChatInteractionApplicationService;
import com.huawei.it.ex.one.chat.application.service.ChatRunApplicationService;
import com.huawei.it.ex.one.chat.application.service.ChatRunTerminalCommitService;
import com.huawei.it.ex.one.chat.application.service.ChatStreamApplicationService;
import com.huawei.it.ex.one.chat.application.service.LocalChatRunExecutionRegistry;
import com.huawei.it.ex.one.chat.application.model.RunStartAttempt;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunStatus;
import com.huawei.it.ex.one.chat.domain.ErrorEvent;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.util.retry.Retry;

/** Closes control state left behind when the first persisted event cannot be handed off. */
@Component
public class FirstEventTimeoutCompensator {
    private static final AppLogger log = AppLoggerFactory.getLogger(FirstEventTimeoutCompensator.class);

    private final ChatInteractionApplicationService interactionService;
    private final ChatRunTerminalCommitService terminalCommitService;
    private final ChatRunApplicationService runService;
    private final ChatStreamApplicationService streamService;
    private final LocalChatRunExecutionRegistry executionRegistry;
    private final Scheduler eventIoScheduler;

    public FirstEventTimeoutCompensator(
            ChatInteractionApplicationService interactionService,
            ChatRunTerminalCommitService terminalCommitService,
            ChatRunApplicationService runService,
            ChatStreamApplicationService streamService,
            LocalChatRunExecutionRegistry executionRegistry,
            @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler) {
        this.interactionService = interactionService;
        this.terminalCommitService = terminalCommitService;
        this.runService = runService;
        this.streamService = streamService;
        this.executionRegistry = executionRegistry;
        this.eventIoScheduler = eventIoScheduler;
    }

    public void schedule(RunStartAttempt startAttempt) {
        if (startAttempt == null || !startAttempt.aborted()) {
            return;
        }
        if (!startAttempt.beginCompensation()) {
            startAttempt.requestCompensationRetry();
            return;
        }
        Mono.defer(() -> Mono.fromCallable(() -> compensate(startAttempt))
                        .subscribeOn(eventIoScheduler))
                .flatMap(outcome -> outcome == CompensationOutcome.RETRY
                        ? Mono.error(new CompensationPendingException(startAttempt.runId()))
                        : Mono.<Void>empty())
                .retryWhen(Retry.backoff(2, Duration.ofMillis(250))
                        .maxBackoff(Duration.ofSeconds(1)))
                .doFinally(ignored -> finishCompensation(startAttempt))
                .subscribe(
                        ignored -> {
                        },
                        error -> log.error(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_TRANSACTION_FAILED,
                                        "First-event timeout compensation did not converge")
                                .runId(startAttempt.runId())
                                .operation("chat-run.first-event-timeout-compensation")
                                .attribute("interactionId", startAttempt.interactionId())
                                .build(), error)
                );
    }

    private void finishCompensation(RunStartAttempt startAttempt) {
        startAttempt.finishCompensation();
        if (startAttempt.consumeCompensationRetry()) {
            schedule(startAttempt);
        }
    }

    private CompensationOutcome compensate(RunStartAttempt startAttempt) {
        log.debug("Run first-event timeout compensation attempt. runId={}, interactionId={}, hasRun={}, hasExecution={}",
                startAttempt.runId(), startAttempt.interactionId(), startAttempt.run() != null,
                startAttempt.executionClaim() != null);
        if (!startAttempt.aborted()) {
            return CompensationOutcome.DONE;
        }
        ChatRun run = startAttempt.run();
        if (run == null) {
            return compensateMissingRun(startAttempt);
        }
        RunExecutionClaim executionClaim = startAttempt.executionClaim();
        if (executionClaim == null && !startAttempt.executionInitializationSkipped()) {
            return CompensationOutcome.RETRY;
        }
        if (terminalCommitService == null) {
            closeWithoutTerminalService(startAttempt);
            return CompensationOutcome.DONE;
        }
        return commitFirstEventTimeout(startAttempt, run, executionClaim);
    }

    private CompensationOutcome compensateMissingRun(RunStartAttempt startAttempt) {
        if (startAttempt.interactionId() == null) {
            return CompensationOutcome.DONE;
        }
        int released = interactionService.markWaitingForRun(
                startAttempt.user().tenantId(), startAttempt.user().ownerUserId(),
                startAttempt.interactionId(), startAttempt.runId());
        return released > 0 ? CompensationOutcome.DONE : CompensationOutcome.RETRY;
    }

    private void closeWithoutTerminalService(RunStartAttempt startAttempt) {
        ChatInteractionRequest interaction = startAttempt.interactionRequest();
        if (interaction != null) {
            interactionService.markWaitingForRun(
                    interaction.tenantId(), interaction.userId(), interaction.id(), startAttempt.runId());
        }
        log.error(SystemErrorLogEntry.builder(SystemErrorCode.CONFIGURATION_INVALID,
                        "Terminal commit service is unavailable for first-event timeout compensation")
                .runId(startAttempt.runId())
                .operation("chat-run.first-event-timeout-compensation")
                .build());
    }

    private CompensationOutcome commitFirstEventTimeout(
            RunStartAttempt startAttempt,
            ChatRun run,
            RunExecutionClaim executionClaim) {
        String message = "等待首个持久化事件超时，本轮执行已终止";
        ChatEvent failed = ErrorEvent.of(
                run.id(),
                run.sessionId(),
                "RUN_FIRST_EVENT_TIMEOUT",
                message,
                Map.of(
                        "code", "RUN_FIRST_EVENT_TIMEOUT",
                        "message", message,
                        "source", "chat-run-start"
                ));
        ChatRunTerminalCommitService.ExternalTerminalCommitResult result =
                terminalCommitService.commitExternalTerminal(
                        ChatRunTerminalCommitService.ExternalTerminalCommitCommand.firstEventTimeout(
                                failed, run, startAttempt.interactionId(), executionClaim));
        if (!result.committed()) {
            return handleRejectedCommit(startAttempt, result);
        }
        runService.synchronizeCommittedRunCache(result.run());
        publishCommittedTimeout(run, result.event());
        completeExecution(startAttempt);
        return CompensationOutcome.DONE;
    }

    private CompensationOutcome handleRejectedCommit(
            RunStartAttempt startAttempt,
            ChatRunTerminalCommitService.ExternalTerminalCommitResult result) {
        if (result.run() != null && (result.run().status().terminal()
                || result.run().status() == ChatRunStatus.CANCELLING)) {
            runService.synchronizeCommittedRunCache(result.run());
            completeExecution(startAttempt);
            return CompensationOutcome.DONE;
        }
        return CompensationOutcome.RETRY;
    }

    private void publishCommittedTimeout(ChatRun run, ChatEvent event) {
        try {
            streamService.publishPersisted(event);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.WEBSOCKET_SEND_FAILED,
                            "First-event timeout terminal committed but realtime publish failed")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.first-event-timeout-publish")
                    .build());
        }
    }

    public void completeExecution(RunStartAttempt startAttempt) {
        RunExecutionClaim executionClaim = startAttempt.executionClaim();
        if (executionClaim == null) {
            executionRegistry.complete(startAttempt.runId());
        } else {
            executionRegistry.complete(executionClaim);
        }
    }

    private enum CompensationOutcome {
        DONE,
        RETRY
    }

    private static final class CompensationPendingException extends RuntimeException {
        private CompensationPendingException(String runId) {
            super("run control state is not ready for first-event timeout compensation: " + runId);
        }
    }
}
