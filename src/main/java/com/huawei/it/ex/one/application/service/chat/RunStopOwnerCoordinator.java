package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.conversation.RunStopControlBus;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;

import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** 当前execution owner接收stop控制命令并把pipeline移交给内存Assembly终态汇总。 */
final class RunStopOwnerCoordinator {
    private static final AppLogger log = AppLoggerFactory.getLogger(RunStopOwnerCoordinator.class);

    private final RunStopControlBus controlBus;
    private final LocalChatRunExecutionRegistry executionRegistry;
    private final ChatRunLeaseApplicationService leaseService;
    private final ChatRunApplicationService chatRunService;
    private final AgentRuntimeExecutor runtimeExecutor;
    private final Scheduler eventIoScheduler;

    RunStopOwnerCoordinator(RunStopControlBus controlBus,
                            LocalChatRunExecutionRegistry executionRegistry,
                            ChatRunLeaseApplicationService leaseService,
                            ChatRunApplicationService chatRunService,
                            AgentRuntimeExecutor runtimeExecutor,
                            Scheduler eventIoScheduler) {
        this.controlBus = controlBus;
        this.executionRegistry = executionRegistry;
        this.leaseService = leaseService;
        this.chatRunService = chatRunService;
        this.runtimeExecutor = runtimeExecutor;
        this.eventIoScheduler = eventIoScheduler;
    }

    @PostConstruct
    void registerRemoteHandler() {
        controlBus.registerHandler(request -> accept(request, controlBus::respond));
    }

    Flux<RunStopControlBus.Response> requestLocal(RunStopControlBus.Request request) {
        Queue<RunStopControlBus.Response> queue = new ArrayBlockingQueue<>(4);
        Sinks.Many<RunStopControlBus.Response> sink =
                Sinks.many().unicast().onBackpressureBuffer(queue);
        accept(request, response -> {
            Sinks.EmitResult result = sink.tryEmitNext(response);
            if (response.terminal() || result.isFailure()) {
                sink.tryEmitComplete();
            }
        });
        return sink.asFlux();
    }

    private void accept(RunStopControlBus.Request request,
                        Consumer<RunStopControlBus.Response> notifier) {
        try {
            eventIoScheduler.schedule(() -> process(request, notifier));
        } catch (RejectedExecutionException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.RESOURCE_EXHAUSTED,
                            "Run owner stop task was rejected by the event scheduler")
                    .runId(request == null ? null : request.runId())
                    .operation("chat-run.stop.owner-schedule")
                    .build(), ex);
            notify(notifier, response(request, RunStopControlBus.Status.UNAVAILABLE, null,
                    "owner stop scheduler is unavailable"));
        }
    }

    private void process(RunStopControlBus.Request request,
                         Consumer<RunStopControlBus.Response> notifier) {
        if (request == null || !leaseService.currentInstanceId().equals(request.ownerInstanceId())) {
            notify(notifier, response(request, RunStopControlBus.Status.NOT_OWNER, null,
                    "stop request target does not match current instance"));
            return;
        }
        LocalChatRunExecutionRegistry.OwnerStopRegistration registration = executionRegistry
                .beginOwnerStop(request, notifier)
                .orElse(null);
        if (registration == null) {
            notify(notifier, response(request, RunStopControlBus.Status.NOT_OWNER, null,
                    "run is not owned by this local execution"));
            return;
        }
        try {
            ChatRun current = chatRunService.requireOwnedRun(
                    registration.context().user(), request.runId());
            if (current.status().terminal()) {
                executionRegistry.abortOwnerStop(request.runId(), request.requestId());
                notify(notifier, response(request, RunStopControlBus.Status.COMMITTED,
                        current.status().name(), "run is already terminal"));
                return;
            }
            ChatRunApplicationService.OwnerStopDecision decision = chatRunService.requestOwnerStop(
                    registration.context().user(), current, request.reason(), registration.claim());
            ChatRun cancelling = decision.run();
            if (!decision.accepted() || cancelling == null || cancelling.status() != ChatRunStatus.CANCELLING) {
                executionRegistry.abortOwnerStop(request.runId(), request.requestId());
                RunStopControlBus.Status status = cancelling != null && cancelling.status().terminal()
                        ? RunStopControlBus.Status.COMMITTED
                        : RunStopControlBus.Status.UNAVAILABLE;
                notify(notifier, response(request, status,
                        cancelling == null ? null : cancelling.status().name(),
                        "run could not enter cancelling state"));
                return;
            }
            if (!executionRegistry.confirmOwnerStop(request.runId(), request.requestId(), cancelling)) {
                notify(notifier, response(request, RunStopControlBus.Status.FAILED,
                        cancelling.status().name(), "local owner stop confirmation was lost"));
                return;
            }
            notify(notifier, response(request, RunStopControlBus.Status.ACCEPTED,
                    cancelling.status().name(), "owner accepted stop request"));
            cancelActiveRelayExchange(registration, cancelling)
                    .doFinally(ignored -> executionRegistry.disposeOwnerStop(
                            request.runId(), request.requestId()))
                    .subscribe();
        } catch (RuntimeException ex) {
            executionRegistry.abortOwnerStop(request.runId(), request.requestId());
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "Run owner failed to accept stop request")
                    .runId(request.runId())
                    .operation("chat-run.stop.owner-accept")
                    .attribute("ownerInstanceId", request.ownerInstanceId())
                    .build(), ex);
            notify(notifier, response(request, RunStopControlBus.Status.FAILED, null,
                    "owner stop acceptance failed"));
        }
    }

    private Mono<Void> cancelActiveRelayExchange(
            LocalChatRunExecutionRegistry.OwnerStopRegistration registration,
            ChatRun run) {
        if (run == null || !"relay".equalsIgnoreCase(run.runtimeProvider())) {
            return Mono.empty();
        }
        return Mono.defer(() -> runtimeExecutor.cancel(
                        run,
                        registration.context().user(),
                        TraceContext.empty(),
                        RuntimeForwardHeaders.empty()))
                .doOnError(error -> logRelayCancelFailure(run, error))
                .onErrorResume(ignored -> Mono.empty());
    }

    private void logRelayCancelFailure(ChatRun run, Throwable error) {
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.RELAY_INTERRUPT_FAILED,
                        "Run owner failed to interrupt active Relay exchange")
                .runId(run.id())
                .sessionId(run.sessionId())
                .operation("chat-run.stop.owner-relay-cancel")
                .build(), error);
    }

    private RunStopControlBus.Response response(RunStopControlBus.Request request,
                                                RunStopControlBus.Status status,
                                                String runStatus,
                                                String message) {
        return new RunStopControlBus.Response(
                request == null ? null : request.requestId(),
                request == null ? null : request.runId(),
                request == null ? null : request.requesterInstanceId(),
                request == null ? leaseService.currentInstanceId() : request.ownerInstanceId(),
                status,
                runStatus,
                message);
    }

    private void notify(Consumer<RunStopControlBus.Response> notifier,
                        RunStopControlBus.Response response) {
        try {
            notifier.accept(response);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_PUBLISH_FAILED,
                            "Run owner stop response notification failed")
                    .runId(response == null ? null : response.runId())
                    .operation("chat-run.stop.owner-notify")
                    .attribute("status", response == null ? null : response.status())
                    .build(), ex);
        }
    }
}
