package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.RuntimeStreamLimitsProperties;
import com.huawei.it.ex.one.application.integration.conversation.RunStopControlBus;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;

import reactor.core.publisher.Flux;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 在固定总时限内尝试把stop移交给当前execution owner。 */
@Component
final class RunStopHandoffCoordinator {
    private static final AppLogger log = AppLoggerFactory.getLogger(RunStopHandoffCoordinator.class);

    private final RuntimeStreamLimitsProperties properties;
    private final RunStopControlBus controlBus;
    private final RunStopOwnerCoordinator ownerCoordinator;
    private final ChatRunLeaseApplicationService leaseService;
    private final ChatRunApplicationService chatRunService;
    private final IdGenerator idGenerator;

    RunStopHandoffCoordinator(RuntimeStreamLimitsProperties properties,
                              RunStopControlBus controlBus,
                              RunStopOwnerCoordinator ownerCoordinator,
                              ChatRunLeaseApplicationService leaseService,
                              ChatRunApplicationService chatRunService,
                              IdGenerator idGenerator) {
        this.properties = properties;
        this.controlBus = controlBus;
        this.ownerCoordinator = ownerCoordinator;
        this.leaseService = leaseService;
        this.chatRunService = chatRunService;
        this.idGenerator = idGenerator;
    }

    Outcome handoff(UserContext user,
                    ChatRun run,
                    String reason,
                    Runnable onAccepted) {
        Duration timeout = properties.getStopOwnerHandoffTimeout();
        long deadline = System.nanoTime() + timeout.toNanos();
        ChatRunExecution firstExecution = leaseService.findExecution(run.id()).orElse(null);
        if (firstExecution == null) {
            return Outcome.unavailable(false);
        }
        HandoffContext context = new HandoffContext(user, reason, onAccepted, deadline);
        AttemptResult first = attempt(context, run, firstExecution);
        if (first.committed() || first.accepted() || remaining(deadline).isZero()) {
            return new Outcome(first.committed(), first.accepted(), false);
        }

        ChatRun latest = chatRunService.requireOwnedRun(user, run.id());
        if (latest.status().terminal()) {
            return Outcome.committed(first.accepted(), false);
        }
        ChatRunExecution refreshed = leaseService.findExecution(run.id()).orElse(null);
        if (refreshed == null || sameOwner(firstExecution, refreshed)) {
            return Outcome.unavailable(first.accepted());
        }
        AttemptResult second = attempt(context, latest, refreshed);
        return new Outcome(second.committed(), first.accepted() || second.accepted(), true);
    }

    private AttemptResult attempt(
            HandoffContext context,
            ChatRun run,
            ChatRunExecution execution) {
        Duration remaining = remaining(context.deadline());
        if (remaining.isZero()) {
            return AttemptResult.unavailable(false);
        }
        RunStopControlBus.Request request = new RunStopControlBus.Request(
                idGenerator.newId("stop",
                        IdGenerateContext.of(context.user().tenantId(), context.user().ownerUserId(),
                                run.sessionId(), run.id())),
                run.id(),
                leaseService.currentInstanceId(),
                execution.ownerInstanceId(),
                execution.fencingToken(),
                context.reason());
        Flux<RunStopControlBus.Response> responses;
        if (leaseService.currentInstanceId().equals(execution.ownerInstanceId())) {
            responses = ownerCoordinator.requestLocal(request);
        } else {
            RunStopControlBus.Delivery delivery = controlBus.send(request);
            responses = delivery.responses();
        }
        AtomicBoolean accepted = new AtomicBoolean(false);
        try {
            List<RunStopControlBus.Response> received = responses
                    .doOnNext(response -> {
                        if (response.status() == RunStopControlBus.Status.ACCEPTED
                                && accepted.compareAndSet(false, true)) {
                            runAcceptedCallback(context.onAccepted(), run);
                        }
                    })
                    .takeUntil(RunStopControlBus.Response::terminal)
                    .timeout(remaining)
                    .collectList()
                    .block();
            RunStopControlBus.Response terminal = received == null || received.isEmpty()
                    ? null
                    : received.getLast();
            return new AttemptResult(
                    terminal != null && terminal.status() == RunStopControlBus.Status.COMMITTED,
                    accepted.get(),
                    terminal == null ? RunStopControlBus.Status.UNAVAILABLE : terminal.status());
        } catch (RuntimeException ex) {
            if (!causedByTimeout(ex)) {
                log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_UNAVAILABLE,
                                "Run stop owner handoff failed; bounded replay will be used")
                        .runId(run.id())
                        .sessionId(run.sessionId())
                        .operation("chat-run.stop.owner-handoff")
                        .attribute("ownerInstanceId", execution.ownerInstanceId())
                        .build(), ex);
            }
            return AttemptResult.unavailable(accepted.get());
        }
    }

    private void runAcceptedCallback(Runnable callback, ChatRun run) {
        if (callback == null) {
            return;
        }
        try {
            callback.run();
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "Downstream cancellation callback failed after owner accepted stop")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.stop.owner-accepted")
                    .build(), ex);
        }
    }

    private Duration remaining(long deadline) {
        long nanos = Math.max(0L, deadline - System.nanoTime());
        return Duration.ofNanos(nanos);
    }

    private boolean sameOwner(ChatRunExecution left, ChatRunExecution right) {
        return left != null && right != null
                && java.util.Objects.equals(left.ownerInstanceId(), right.ownerInstanceId())
                && left.fencingToken() == right.fencingToken();
    }

    private boolean causedByTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    record Outcome(boolean committed, boolean accepted, boolean rerouted) {
        static Outcome committed(boolean accepted, boolean rerouted) {
            return new Outcome(true, accepted, rerouted);
        }

        static Outcome unavailable(boolean accepted) {
            return new Outcome(false, accepted, false);
        }
    }

    private record AttemptResult(boolean committed, boolean accepted, RunStopControlBus.Status status) {
        static AttemptResult unavailable(boolean accepted) {
            return new AttemptResult(false, accepted, RunStopControlBus.Status.UNAVAILABLE);
        }
    }

    private record HandoffContext(
            UserContext user,
            String reason,
            Runnable onAccepted,
            long deadline
    ) {
    }
}
