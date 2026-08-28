package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.RunCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RunStartedEvent;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Persists run.started before allowing routing or Runtime side effects. */
final class ChatRunExecutionGateCoordinator {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunExecutionGateCoordinator.class);
    private final ChatRunStartCoordinator runStartCoordinator;
    private final ChatRunLeaseApplicationService chatRunLeaseService;
    private final LocalChatRunExecutionRegistry runExecutionRegistry;
    private final ChatEventPipeline chatEventPipeline;
    private final Scheduler eventIoScheduler;
    private final ChatRunFailureMapper runFailureMapper = new ChatRunFailureMapper();

    ChatRunExecutionGateCoordinator(ChatRunStartCoordinator runStartCoordinator,
                                    ChatRunLeaseApplicationService chatRunLeaseService,
                                    LocalChatRunExecutionRegistry runExecutionRegistry,
                                    ChatEventPipeline chatEventPipeline,
                                    Scheduler eventIoScheduler) {
        this.runStartCoordinator = runStartCoordinator;
        this.chatRunLeaseService = chatRunLeaseService;
        this.runExecutionRegistry = runExecutionRegistry;
        this.chatEventPipeline = chatEventPipeline;
        this.eventIoScheduler = eventIoScheduler;
    }

    Flux<ChatEvent> execute(
            RunEventPipelineContext context,
            Supplier<Flux<ChatEvent>> bodySupplier,
            Function<ChatEvent, Mono<ChatEvent>> singleEventWriter) {
        return persistRunStartedGate(context, singleEventWriter).flatMapMany(outcome -> {
            if (outcome.status() == RunStartGateStatus.REJECTED) {
                log.info("Chat run start gate rejected execution; skip route and runtime side effects. runId={}",
                        context.runId());
                return Flux.empty();
            }
            if (outcome.status() == RunStartGateStatus.TERMINATED) {
                return Flux.just(outcome.event());
            }
            Flux<ChatEvent> runtimeBody = Flux.defer(bodySupplier)
                    .doOnNext(event -> {
                        if (event != null && "run.async_running".equals(event.type())) {
                            context.asyncRunningObserved().set(true);
                        }
                    });
            Flux<ChatEvent> body = Flux.concat(
                            Mono.fromRunnable(() -> runStartCoordinator.ensureActive(
                                            context.startAttempt(), "after-run-started"))
                                    .then(requireCurrentOwnerRunning(
                                            context.executionClaim(), "after-run-started"))
                                    .thenMany(runtimeBody),
                            Flux.defer(() -> context.asyncRunningObserved().get()
                                    ? Flux.empty()
                                    : Flux.just(RunCompletedEvent.of(
                                            context.runId(),
                                            context.session().id(),
                                            runCompletedPayload(
                                                    context.routeRef().get(),
                                                    context.bindingRef().get())))))
                    .onErrorResume(ChatEventAppendRejectedException.class, ex -> {
                        log.info("Chat run owner lost before external side effect; stop local flow. runId={}, reason={}",
                                context.runId(), ex.getMessage());
                        return Flux.empty();
                    })
                    .onErrorResume(ex -> Flux.just(runFailureMapper.toEvent(
                            context.runId(), context.session().id(), ex)));
            return Flux.concat(
                    Flux.just(outcome.event()),
                    persistAndPublish(body, context, singleEventWriter));
        }).doFinally(ignored -> runExecutionRegistry.complete(context.executionClaim()));
    }

    Mono<Void> requireCurrentOwnerRunning(RunExecutionClaim claim, String stage) {
        return Mono.fromCallable(() -> {
                    if (!chatRunLeaseService.isCurrentOwnerRunning(claim)) {
                        throw new ChatEventAppendRejectedException(
                                "run execution owner 已失效: runId="
                                        + (claim == null ? null : claim.runId())
                                        + ", stage=" + stage);
                    }
                    return true;
                })
                .subscribeOn(eventIoScheduler)
                .then();
    }

    private Mono<RunStartGateOutcome> persistRunStartedGate(
            RunEventPipelineContext context,
            Function<ChatEvent, Mono<ChatEvent>> singleEventWriter) {
        return persistAndPublish(
                        Flux.just(RunStartedEvent.of(context.runId(), context.session().id())),
                        context,
                        singleEventWriter)
                .singleOrEmpty()
                .map(event -> "run.started".equals(event.type())
                        ? RunStartGateOutcome.admitted(event)
                        : RunStartGateOutcome.terminated(event))
                .defaultIfEmpty(RunStartGateOutcome.rejected());
    }

    private Flux<ChatEvent> persistAndPublish(
            Flux<ChatEvent> events,
            RunEventPipelineContext context,
            Function<ChatEvent, Mono<ChatEvent>> singleEventWriter) {
        return chatEventPipeline.persistAndPublish(events, context, singleEventWriter);
    }

    private Map<String, Object> runCompletedPayload(RouteTarget route, RuntimeBinding binding) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "COMPLETED");
        if (route != null && route.type() != null) {
            payload.put("routeType", route.type().name());
        }
        if (route != null && route.routeSource() != null) {
            payload.put("routeSource", route.routeSource());
        }
        if (route != null && route.selectedAgentCode() != null) {
            payload.put("agentCode", route.selectedAgentCode());
        }
        if (binding != null) {
            payload.put("runtimeBindingId", binding.id());
            payload.put("runtimeProvider", binding.provider());
            if (binding.runtimeSessionId() != null) {
                payload.put("runtimeSessionId", binding.runtimeSessionId());
            }
        }
        return payload;
    }

    private enum RunStartGateStatus {
        ADMITTED,
        REJECTED,
        TERMINATED
    }

    private record RunStartGateOutcome(RunStartGateStatus status, ChatEvent event) {
        private static RunStartGateOutcome admitted(ChatEvent event) {
            return new RunStartGateOutcome(RunStartGateStatus.ADMITTED, event);
        }

        private static RunStartGateOutcome rejected() {
            return new RunStartGateOutcome(RunStartGateStatus.REJECTED, null);
        }

        private static RunStartGateOutcome terminated(ChatEvent event) {
            return new RunStartGateOutcome(RunStartGateStatus.TERMINATED, event);
        }
    }
}
