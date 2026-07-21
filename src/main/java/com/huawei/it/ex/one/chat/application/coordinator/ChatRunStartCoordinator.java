package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.common.id.IdGenerateContext;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.chat.application.service.LocalChatRunExecutionRegistry;
import com.huawei.it.ex.one.chat.application.service.RunAdmissionControlService;
import com.huawei.it.ex.one.chat.application.model.RunStartAttempt;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunStartResult;
import com.huawei.it.ex.one.chat.domain.ChatStreamTopics;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** Owns background subscription and first-event handoff for newly started runs. */
@Component
public class ChatRunStartCoordinator {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunStartCoordinator.class);

    private final IdGenerator idGenerator;
    private final RunAdmissionControlService admissionControl;
    private final LocalChatRunExecutionRegistry executionRegistry;
    private final ChatRunOperationalProperties operationalProperties;
    private final FirstEventTimeoutCompensator timeoutCompensator;

    public ChatRunStartCoordinator(
            IdGenerator idGenerator,
            RunAdmissionControlService admissionControl,
            LocalChatRunExecutionRegistry executionRegistry,
            ChatRunOperationalProperties operationalProperties,
            FirstEventTimeoutCompensator timeoutCompensator) {
        this.idGenerator = idGenerator;
        this.admissionControl = admissionControl;
        this.executionRegistry = executionRegistry;
        this.operationalProperties = operationalProperties == null
                ? new ChatRunOperationalProperties()
                : operationalProperties;
        this.timeoutCompensator = timeoutCompensator;
    }

    public Mono<ChatRunStartResult> startStandard(
            UserContext user,
            TraceContext traceContext,
            ChatCommand command,
            StandardRunFactory runFactory) {
        return Mono.defer(() -> {
            String runId = idGenerator.newId("run",
                    IdGenerateContext.of(user.tenantId(), user.ownerUserId(), command.sessionId()));
            RunStartAttempt attempt = new RunStartAttempt(user, runId, null);
            BackgroundStartState state = newState(user, attempt);
            Flux<ChatEvent> runFlux = runFactory.create(attempt)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(event -> onEvent(event, state, "chat run"))
                    .doOnComplete(() -> onComplete(
                            state, "chat run finished before emitting any persisted event"))
                    .doFinally(ignored -> finish(state));
            subscribeStandard(runFlux, state, traceContext);
            return firstEventResult(state, "chat run");
        });
    }

    public Mono<ChatRunStartResult> startInteraction(
            UserContext user,
            TraceContext traceContext,
            String interactionId,
            InteractionRunFactory runFactory) {
        return Mono.defer(() -> {
            String runId = idGenerator.newId("run",
                    IdGenerateContext.of(user.tenantId(), user.ownerUserId(), interactionId));
            RunStartAttempt attempt = new RunStartAttempt(user, runId, interactionId);
            BackgroundStartState state = newState(user, attempt);
            Flux<ChatEvent> runFlux = runFactory.create(runId, attempt)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(event -> onEvent(event, state, "interaction continuation"))
                    .doOnComplete(() -> onComplete(
                            state, "interaction continuation finished before emitting any event"))
                    .doFinally(ignored -> finish(state));
            subscribeInteraction(runFlux, state, traceContext, interactionId);
            return firstEventResult(state, "interaction continuation");
        });
    }

    private BackgroundStartState newState(UserContext user, RunStartAttempt attempt) {
        return new BackgroundStartState(
                attempt,
                new RunPermitGuard(admissionControl.acquire(user)),
                Sinks.one(),
                new AtomicReference<>(),
                new AtomicReference<>(),
                new AtomicBoolean(false));
    }

    private void onEvent(ChatEvent event, BackgroundStartState state, String operation) {
        RunStartAttempt attempt = state.attempt();
        if (!attempt.beginFirstEventHandoff()) {
            return;
        }
        if (state.runId().compareAndSet(null, event.runId())) {
            registerKnownRun(state, event.runId());
        }
        Sinks.EmitResult emitted = state.firstEvent().tryEmitValue(event);
        if (emitted.isFailure() && attempt.abortFailedHandoff()) {
            abortStartAttempt(state, operation);
        }
    }

    private void onComplete(BackgroundStartState state, String message) {
        if (state.runId().get() == null) {
            state.firstEvent().tryEmitError(new IllegalStateException(message));
        }
    }

    private void finish(BackgroundStartState state) {
        state.terminal().set(true);
        state.permit().close();
    }

    private void subscribeStandard(
            Flux<ChatEvent> runFlux,
            BackgroundStartState state,
            TraceContext traceContext) {
        Disposable disposable = runFlux.subscribe(
                ignored -> {
                },
                error -> {
                    Sinks.EmitResult result = state.firstEvent().tryEmitError(error);
                    if (result.isFailure() && state.runId().get() != null) {
                        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                                        "Background chat run terminated after first-event handoff")
                                .traceId(traceContext.traceId())
                                .runId(state.runId().get())
                                .operation("chat-run.background")
                                .build(), error);
                    }
                });
        registerSubscription(state, disposable);
    }

    private void subscribeInteraction(
            Flux<ChatEvent> runFlux,
            BackgroundStartState state,
            TraceContext traceContext,
            String interactionId) {
        Disposable disposable = runFlux.subscribe(
                ignored -> {
                },
                error -> {
                    Sinks.EmitResult result = state.firstEvent().tryEmitError(error);
                    if (result.isFailure()) {
                        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                                        "Background Interaction continuation terminated after first-event handoff")
                                .traceId(traceContext.traceId())
                                .runId(state.attempt().runId())
                                .operation("interaction.background")
                                .attribute("interactionId", interactionId)
                                .build(), error);
                    }
                });
        registerSubscription(state, disposable);
    }

    private void registerSubscription(BackgroundStartState state, Disposable disposable) {
        state.disposable().set(disposable);
        String runId = state.runId().get();
        if (runId != null && !state.terminal().get()) {
            executionRegistry.register(runId, disposable);
        }
    }

    private void registerKnownRun(BackgroundStartState state, String runId) {
        Disposable disposable = state.disposable().get();
        if (disposable != null && !state.terminal().get()) {
            executionRegistry.register(runId, disposable);
        }
    }

    private Mono<ChatRunStartResult> firstEventResult(BackgroundStartState state, String operation) {
        return awaitFirstEvent(state, operation)
                .map(event -> new ChatRunStartResult(
                        event.runId(),
                        event.sessionId(),
                        event.sequence(),
                        event.createdAt(),
                        ChatStreamTopics.runTopic(event.runId())));
    }

    private Mono<ChatEvent> awaitFirstEvent(BackgroundStartState state, String operation) {
        return withFirstEventTimeout(
                state.firstEvent().asMono(),
                operationalProperties.normalizedFirstEventTimeout(),
                () -> abortBeforeFirstEvent(state, operation));
    }

    public static <T> Mono<T> withFirstEventTimeout(Mono<T> source, Duration timeout, Runnable abort) {
        AtomicBoolean aborted = new AtomicBoolean(false);
        Runnable abortOnce = () -> {
            if (aborted.compareAndSet(false, true)) {
                abort.run();
            }
        };
        Mono<T> handoff = source.doOnCancel(abortOnce);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return handoff;
        }
        return handoff.timeout(timeout, Mono.error(new IllegalStateException(
                "RUN_FIRST_EVENT_TIMEOUT: 等待首个持久化事件超时: " + timeout)));
    }

    private void abortBeforeFirstEvent(BackgroundStartState state, String operation) {
        if (!state.attempt().abort()) {
            return;
        }
        abortStartAttempt(state, operation);
    }

    private void abortStartAttempt(BackgroundStartState state, String operation) {
        boolean firstAbort = state.permit().closeOnce();
        Disposable disposable = state.disposable().get();
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
        RunExecutionClaim executionClaim = state.attempt().executionClaim();
        if (executionClaim != null) {
            executionRegistry.complete(executionClaim);
        }
        timeoutCompensator.schedule(state.attempt());
        if (firstAbort) {
            log.warn("Abort {} before first-event handoff. runId={}", operation, state.attempt().runId());
        }
    }

    public void trackRun(RunStartAttempt attempt, ChatRun run, String stage) {
        if (attempt == null) {
            return;
        }
        attempt.recordRun(run);
        if (attempt.aborted()) {
            attempt.markExecutionInitializationSkipped();
            timeoutCompensator.schedule(attempt);
            throw rejected(attempt, stage);
        }
    }

    public void trackExecution(RunStartAttempt attempt, RunExecutionClaim executionClaim, String stage) {
        if (attempt != null) {
            attempt.recordExecutionClaim(executionClaim);
        }
        executionRegistry.registerClaim(executionClaim);
        if (attempt != null && attempt.aborted()) {
            executionRegistry.complete(executionClaim);
            timeoutCompensator.schedule(attempt);
            throw rejected(attempt, stage);
        }
    }

    public void ensureActive(RunStartAttempt attempt, String stage) {
        if (attempt == null || !attempt.aborted()) {
            return;
        }
        if (attempt.run() != null && attempt.executionClaim() == null) {
            attempt.markExecutionInitializationSkipped();
            timeoutCompensator.schedule(attempt);
        }
        throw rejected(attempt, stage);
    }

    private RuntimeException rejected(RunStartAttempt attempt, String stage) {
        return new com.huawei.it.ex.one.chat.application.model.ChatEventAppendRejectedException(
                "run start attempt 已在首事件交接前终止: runId=" + attempt.runId() + ", stage=" + stage);
    }

    @FunctionalInterface
    public interface StandardRunFactory {
        Flux<ChatEvent> create(RunStartAttempt attempt);
    }

    @FunctionalInterface
    public interface InteractionRunFactory {
        Flux<ChatEvent> create(String runId, RunStartAttempt attempt);
    }

    private record BackgroundStartState(
            RunStartAttempt attempt,
            RunPermitGuard permit,
            Sinks.One<ChatEvent> firstEvent,
            AtomicReference<Disposable> disposable,
            AtomicReference<String> runId,
            AtomicBoolean terminal
    ) {
    }

    private static final class RunPermitGuard implements AutoCloseable {
        private final RunAdmissionControlService.Permit delegate;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private RunPermitGuard(RunAdmissionControlService.Permit delegate) {
            this.delegate = delegate == null ? RunAdmissionControlService.Permit.NOOP : delegate;
        }

        @Override
        public void close() {
            closeOnce();
        }

        private boolean closeOnce() {
            if (closed.compareAndSet(false, true)) {
                delegate.close();
                return true;
            }
            return false;
        }
    }
}
