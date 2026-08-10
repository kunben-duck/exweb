package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.RuntimeStreamLimitsProperties;
import com.huawei.it.ex.one.application.integration.conversation.RunStopControlBus;
import com.huawei.it.ex.one.application.service.runtime.RuntimePendingEventGuard;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatRun;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;

/** 在运行pipeline退出后使用原Assembly完成owner stop终态提交。 */
final class ChatRunOwnerStopFinalizer {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunOwnerStopFinalizer.class);

    private final ChatRunStopTerminalFinalizer terminalFinalizer;
    private final LocalChatRunExecutionRegistry executionRegistry;
    private final RuntimePendingEventGuard pendingEventGuard;
    private final Scheduler eventIoScheduler;
    private final Duration finalizationLease;

    ChatRunOwnerStopFinalizer(ChatRunStopTerminalFinalizer terminalFinalizer,
                              LocalChatRunExecutionRegistry executionRegistry,
                              RuntimePendingEventGuard pendingEventGuard,
                              Scheduler eventIoScheduler) {
        this(terminalFinalizer, executionRegistry, pendingEventGuard, eventIoScheduler,
                new RuntimeStreamLimitsProperties());
    }

    ChatRunOwnerStopFinalizer(ChatRunStopTerminalFinalizer terminalFinalizer,
                              LocalChatRunExecutionRegistry executionRegistry,
                              RuntimePendingEventGuard pendingEventGuard,
                              Scheduler eventIoScheduler,
                              RuntimeStreamLimitsProperties streamLimits) {
        this.terminalFinalizer = terminalFinalizer;
        this.executionRegistry = executionRegistry;
        this.pendingEventGuard = pendingEventGuard;
        this.eventIoScheduler = eventIoScheduler;
        this.finalizationLease = streamLimits == null
                ? Duration.ofSeconds(15)
                : streamLimits.getStopFinalizationLease();
    }

    void finalizeAsync(LocalChatRunExecutionRegistry.OwnerStopFinalization finalization) {
        finalization.cancellingRun()
                .timeout(finalizationLease)
                .flatMap(cancellingRun -> Mono.fromCallable(() -> terminalFinalizer.commit(
                        finalization.context().user(),
                        cancellingRun,
                        finalization.request().reason(),
                        finalization.context().session(),
                        finalization.context().assistant(),
                        finalization.claim())))
                .subscribeOn(eventIoScheduler)
                .doFinally(ignored -> cleanup(finalization))
                .subscribe(
                        result -> notifyResult(finalization, result),
                        error -> notifyFailure(finalization, error));
    }

    private void notifyResult(LocalChatRunExecutionRegistry.OwnerStopFinalization finalization,
                              ChatRunStopTerminalFinalizer.Result result) {
        ChatRun run = result == null ? null : result.run();
        RunStopControlBus.Status status = run != null && run.status().terminal()
                ? RunStopControlBus.Status.COMMITTED
                : RunStopControlBus.Status.FAILED;
        notify(finalization, response(
                finalization,
                status,
                run == null ? null : run.status().name(),
                result != null && result.committed() ? "owner stop committed" : "terminal already claimed"));
    }

    private void notifyFailure(LocalChatRunExecutionRegistry.OwnerStopFinalization finalization, Throwable error) {
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_TRANSACTION_FAILED,
                        "Owner stop terminal finalization failed")
                .runId(finalization.request().runId())
                .sessionId(finalization.context().session().id())
                .operation("chat-run.stop.owner-finalize")
                .attribute("ownerInstanceId", finalization.claim().ownerInstanceId())
                .build(), error);
        notify(finalization, response(
                finalization, RunStopControlBus.Status.FAILED, null, "owner stop finalization failed"));
    }

    private void notify(LocalChatRunExecutionRegistry.OwnerStopFinalization finalization,
                        RunStopControlBus.Response response) {
        try {
            finalization.notifier().accept(response);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_PUBLISH_FAILED,
                            "Owner stop terminal notification failed")
                    .runId(finalization.request().runId())
                    .sessionId(finalization.context().session().id())
                    .operation("chat-run.stop.owner-finalize-notify")
                    .attribute("status", response == null ? null : response.status())
                    .build(), ex);
        }
    }

    private void cleanup(LocalChatRunExecutionRegistry.OwnerStopFinalization finalization) {
        try {
            finalization.context().assistant().close();
        } finally {
            if (pendingEventGuard != null) {
                pendingEventGuard.releaseRun(finalization.context().runId());
            }
            executionRegistry.completeOwnerStopFinalization(finalization.claim());
        }
    }

    private RunStopControlBus.Response response(
            LocalChatRunExecutionRegistry.OwnerStopFinalization finalization,
            RunStopControlBus.Status status,
            String runStatus,
            String message) {
        RunStopControlBus.Request request = finalization.request();
        return new RunStopControlBus.Response(
                request.requestId(), request.runId(), request.requesterInstanceId(), request.ownerInstanceId(),
                status, runStatus, message);
    }
}
