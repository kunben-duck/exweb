package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.RuntimeStreamLimitsProperties;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;

/** execution owner不可用时，以硬边界分页重放已持久化Event并完成stop终态。 */
@Component
final class ChatRunStopReplayService {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunStopReplayService.class);

    private final RuntimeStreamLimitsProperties properties;
    private final ChatStreamApplicationService streamService;
    private final ChatRunApplicationService chatRunService;
    private final AssistantAssemblyFactory assistantFactory;
    private final ChatRunStopTerminalFinalizer terminalFinalizer;
    private final Semaphore replayPermits;

    ChatRunStopReplayService(RuntimeStreamLimitsProperties properties,
                             ChatStreamApplicationService streamService,
                             ChatRunApplicationService chatRunService,
                             AssistantAssemblyFactory assistantFactory,
                             ChatRunStopTerminalFinalizer terminalFinalizer) {
        this.properties = properties;
        this.streamService = streamService;
        this.chatRunService = chatRunService;
        this.assistantFactory = assistantFactory;
        this.terminalFinalizer = terminalFinalizer;
        this.replayPermits = new Semaphore(properties.getStopReplayMaxConcurrency(), true);
    }

    ChatRunStopTerminalFinalizer.Result replayAndCommit(
            UserContext user,
            ChatRun requestedRun,
            String reason,
            ChatSession sessionSnapshot) {
        if (!replayPermits.tryAcquire()) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.RESOURCE_EXHAUSTED,
                            "Stop event replay concurrency limit reached; cancel without partial assistant")
                    .runId(requestedRun.id())
                    .sessionId(requestedRun.sessionId())
                    .operation("chat-run.stop.replay-admission")
                    .attribute("maxConcurrency", properties.getStopReplayMaxConcurrency())
                    .build());
            ChatRun cancelling = ensureCancelling(user, requestedRun, reason);
            return terminalFinalizer.commit(user, cancelling, reason, sessionSnapshot, null, null);
        }
        AssistantAssembly assistant = null;
        try {
            ChatRun latest = chatRunService.requireOwnedRun(user, requestedRun.id());
            if (latest.status().terminal()) {
                return new ChatRunStopTerminalFinalizer.Result(latest, null, false,
                        ChatRunStopAssistantProjector.Projection.notReady());
            }
            ChatRun cancelling = ensureCancelling(user, latest, reason);
            if (cancelling.status().terminal()) {
                return new ChatRunStopTerminalFinalizer.Result(cancelling, null, false,
                        ChatRunStopAssistantProjector.Projection.notReady());
            }
            ReplayOutcome replay = replay(user, cancelling);
            assistant = replay.complete() ? replay.assistant() : null;
            if (!replay.complete() && replay.assistant() != null) {
                replay.assistant().close();
            }
            return terminalFinalizer.commit(user, cancelling, reason, sessionSnapshot, assistant, null);
        } finally {
            if (assistant != null) {
                assistant.close();
            }
            replayPermits.release();
        }
    }

    private ReplayOutcome replay(UserContext user, ChatRun run) {
        AssistantAssembly assistant = assistantFactory.create(
                run.id(), AgentDataPersistenceState.fromRunMetadata(run.metadata(), null));
        long cursor = run.firstSeq() == null || run.firstSeq() <= 0L ? 0L : run.firstSeq() - 1L;
        int scanned = 0;
        long started = System.nanoTime();
        Duration totalTimeout = properties.getStopReplayTotalTimeout();
        try {
            while (!deadlineExceeded(started, totalTimeout)) {
                if (scanned > 0 && !enoughTimeForAnotherQuery(started, totalTimeout)) {
                    return reject(run, assistant, "TOTAL_TIMEOUT");
                }
                int remaining = properties.getStopReplayMaxEventsPerRun() - scanned;
                if (remaining <= 0) {
                    List<ChatEvent> overflow = streamService.findPersistedRunEventPage(
                            user, run, cursor, 1);
                    if (overflow.isEmpty()) {
                        return ReplayOutcome.complete(assistant);
                    }
                    return reject(run, assistant, "EVENT_LIMIT");
                }
                int pageSize = Math.min(properties.getStopReplayPageSize(), remaining);
                List<ChatEvent> page = streamService.findPersistedRunEventPage(
                        user, run, cursor, pageSize);
                if (page.isEmpty()) {
                    return ReplayOutcome.complete(assistant);
                }
                for (ChatEvent event : page) {
                    scanned++;
                    cursor = Math.max(cursor, event.sequence());
                    AssistantAssembly.ObservationResult observation = assistant.observe(event);
                    if (observation.essentialOverflow()) {
                        return reject(run, assistant, "ASSISTANT_LIMIT");
                    }
                }
                if (page.size() < pageSize) {
                    return ReplayOutcome.complete(assistant);
                }
            }
            return reject(run, assistant, "TOTAL_TIMEOUT");
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_READ_FAILED,
                            "Stop event replay failed; cancel without partial assistant")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.stop.replay")
                    .attribute("scannedEvents", scanned)
                    .build(), ex);
            return ReplayOutcome.incomplete(assistant);
        }
    }

    private ReplayOutcome reject(ChatRun run, AssistantAssembly assistant, String reason) {
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.RESOURCE_EXHAUSTED,
                        "Stop event replay exceeded its hard boundary; cancel without partial assistant")
                .runId(run.id())
                .sessionId(run.sessionId())
                .operation("chat-run.stop.replay-limit")
                .attribute("limitType", reason)
                .build());
        return ReplayOutcome.incomplete(assistant);
    }

    private ChatRun ensureCancelling(UserContext user, ChatRun run, String reason) {
        if (run.status() == ChatRunStatus.CANCELLING || run.status().terminal()) {
            return run;
        }
        return chatRunService.requestStop(user, run, reason).run();
    }

    private boolean deadlineExceeded(long startedNanos, Duration timeout) {
        return timeout != null && System.nanoTime() - startedNanos >= timeout.toNanos();
    }

    private boolean enoughTimeForAnotherQuery(long startedNanos, Duration timeout) {
        if (timeout == null) {
            return true;
        }
        long remainingNanos = timeout.toNanos() - (System.nanoTime() - startedNanos);
        return remainingNanos >= Duration.ofSeconds(
                properties.getStopReplayQueryTimeoutSeconds()).toNanos();
    }

    int availableReplayPermits() {
        return replayPermits.availablePermits();
    }

    private record ReplayOutcome(AssistantAssembly assistant, boolean complete) {
        private static ReplayOutcome complete(AssistantAssembly assistant) {
            return new ReplayOutcome(assistant, true);
        }

        private static ReplayOutcome incomplete(AssistantAssembly assistant) {
            return new ReplayOutcome(assistant, false);
        }
    }
}
