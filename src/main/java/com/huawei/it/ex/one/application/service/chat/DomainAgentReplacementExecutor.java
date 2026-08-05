package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceGate;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentBindingCommand;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingResolution;
import com.huawei.it.ex.one.application.service.runtime.RuntimeExecutionContext;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.util.retry.Retry;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Applies a resolved replacement Runtime after a DomainAgent refusal. */
final class DomainAgentReplacementExecutor {
    private static final AppLogger log = AppLoggerFactory.getLogger(DomainAgentReplacementExecutor.class);

    private final AgentRuntimeExecutor agentRuntimeExecutor;
    private final RuntimeBindingApplicationService runtimeBindingService;
    private final AppliedRouteRecorder appliedRouteRecorder;
    private final RouteResolutionCoordinator routeResolutionCoordinator;
    private final DomainAgentBindingPolicy bindingPolicy;
    private final DomainAgentRefusalEventFactory eventFactory;
    private final ChatRunLeaseApplicationService chatRunLeaseService;
    private final Scheduler eventIoScheduler;
    private final Scheduler controlIoScheduler;
    private final DomainAgentProperties domainAgentProperties;
    private final RuntimeBindingDispatchCompensator bindingCompensator;
    private final AgentDataPersistenceGate persistenceGate;

    DomainAgentReplacementExecutor(AgentRuntimeExecutor agentRuntimeExecutor,
                                   RuntimeBindingApplicationService runtimeBindingService,
                                   AppliedRouteRecorder appliedRouteRecorder,
                                   RouteResolutionCoordinator routeResolutionCoordinator,
                                   DomainAgentBindingPolicy bindingPolicy,
                                   DomainAgentRefusalEventFactory eventFactory,
                                   ChatRunLeaseApplicationService chatRunLeaseService,
                                   Scheduler eventIoScheduler,
                                   Scheduler controlIoScheduler,
                                   DomainAgentProperties domainAgentProperties,
                                   RuntimeBindingDispatchCompensator bindingCompensator,
                                   AgentDataPersistenceGate persistenceGate) {
        this.agentRuntimeExecutor = agentRuntimeExecutor;
        this.runtimeBindingService = runtimeBindingService;
        this.appliedRouteRecorder = appliedRouteRecorder;
        this.routeResolutionCoordinator = routeResolutionCoordinator;
        this.bindingPolicy = bindingPolicy;
        this.eventFactory = eventFactory;
        this.chatRunLeaseService = chatRunLeaseService;
        this.eventIoScheduler = eventIoScheduler;
        this.controlIoScheduler = controlIoScheduler == null ? eventIoScheduler : controlIoScheduler;
        this.domainAgentProperties = domainAgentProperties == null
                ? new DomainAgentProperties()
                : domainAgentProperties;
        this.bindingCompensator = bindingCompensator;
        this.persistenceGate = persistenceGate;
    }

    Flux<ChatEvent> continueWithRelay(DomainAgentRerouteContext reroute,
                                      RouteSignalResult signal,
                                      RouteTarget nextRoute) {
        DomainAgentRunContext context = reroute.context();
        recordIntent(context, signal.intentDecision(), nextRoute);
        String currentRouteSource = bindingPolicy.routeSource(context.bindingRef().get());
        if (bindingPolicy.requiresRouteSwitchConfirmation(currentRouteSource)) {
            return Flux.just(eventFactory.routeSwitchConfirmationRequest(
                    reroute,
                    signal,
                    currentRouteSource));
        }
        RuntimeBindingDispatchLifecycle lifecycle = new RuntimeBindingDispatchLifecycle();
        return requireCurrentOwnerRunning(
                context.executionClaim(), "before-relay-reroute-binding")
                .thenMany(Flux.usingWhen(
                        Mono.just(lifecycle),
                        ignored -> Flux.defer(() -> executeRelayReplacement(
                                reroute, signal, nextRoute, lifecycle)),
                        ignored -> cleanupUnstartedRelay(context, lifecycle, "complete"),
                        (ignored, failure) -> cleanupUnstartedRelay(context, lifecycle, "error"),
                        ignored -> cleanupUnstartedRelay(context, lifecycle, "cancel")));
    }

    private Flux<ChatEvent> executeRelayReplacement(
            DomainAgentRerouteContext reroute,
            RouteSignalResult signal,
            RouteTarget nextRoute,
            RuntimeBindingDispatchLifecycle lifecycle) {
        DomainAgentRunContext context = reroute.context();
        context.bindingRef().set(bindingPolicy.markRejected(
                context.bindingRef().get(), reroute.refusal()));
        RuntimeBindingResolution resolution = runtimeBindingService.resolveForProfile(
                new RuntimeBindingApplicationService.ProfiledRunBindingRequest(
                        context.user().tenantId(),
                        context.user().ownerUserId(),
                        context.session().id(),
                        context.runId(),
                        bindingPolicy.runtimeBindingLeafId(context.command()),
                        nextRoute.runtimeProfile(),
                        nextRoute.runtimeRoleName()));
        trackRelayBinding(lifecycle, resolution);
        context.bindingRef().set(resolution.binding());
        context.routeRef().set(nextRoute);
        appliedRouteRecorder.bindResolvedRouteRequired(
                context.runId(), nextRoute, resolution.binding(), context.executionClaim(),
                context.persistenceState());
        MemoryContext runtimeMemory = recordAppliedRoute(reroute, signal, nextRoute, resolution.binding());
        ChatCommand runtimeCommand = runtimeCommand(context, nextRoute, signal.intentDecision());
        String action = signal.intentFailure() ? "RELAY_FALLBACK" : "ROUTE_TO_RELAY";
        Sinks.One<Void> reroutePersisted = Sinks.one();
        ChatEvent rerouteEvent = new PersistenceAcknowledgedEvent(
                eventFactory.rerouteMetadata(context, reroute.refusal(), nextRoute, action),
                reroutePersisted);
        RelayReplacementExecution execution = new RelayReplacementExecution(
                signal,
                nextRoute,
                resolution,
                runtimeCommand,
                runtimeMemory);
        return requireCurrentOwnerRunning(
                context.executionClaim(), "before-relay-reroute-runtime")
                .thenMany(Flux.concat(
                        Flux.just(rerouteEvent),
                        // Relay 只能在重路由事件确认落库后订阅，写入拒绝时恢复或取消本轮 Binding。
                        reroutePersisted.asMono()
                                .publishOn(eventIoScheduler)
                                .thenMany(relayReplacementRuntime(
                                        context,
                                        execution,
                                        lifecycle))));
    }

    private Flux<ChatEvent> relayReplacementRuntime(
            DomainAgentRunContext context,
            RelayReplacementExecution execution,
            RuntimeBindingDispatchLifecycle lifecycle) {
        return Flux.defer(() -> {
            appliedRouteRecorder.markRuntimeDispatchStartedRequired(
                    context.runId(),
                    execution.nextRoute(),
                    execution.resolution().binding(),
                    context.executionClaim(),
                    context.persistenceState());
            return agentRuntimeExecutor.execute(new RuntimeExecutionContext(
                        execution.runtimeCommand(),
                        context.runId(),
                        execution.runtimeMemory(),
                        execution.signal().intentDecision(),
                        execution.nextRoute(),
                        context.user(),
                        execution.resolution().binding(),
                        execution.resolution().sessionMode(),
                        context.forwardHeaders(),
                        context.documents(),
                        context.traceContext()));
        })
                .doOnSubscribe(ignored -> lifecycle.markRuntimeSubscribed());
    }

    private void trackRelayBinding(
            RuntimeBindingDispatchLifecycle lifecycle,
            RuntimeBindingResolution resolution) {
        if (resolution.previousBinding() == null) {
            lifecycle.trackCreated(resolution.binding());
        } else {
            lifecycle.trackReused(resolution.binding(), resolution.previousBinding());
        }
    }

    private Mono<Void> cleanupUnstartedRelay(
            DomainAgentRunContext context,
            RuntimeBindingDispatchLifecycle lifecycle,
            String terminationSignal) {
        return bindingCompensator.cleanup(
                lifecycle,
                context.runId(),
                context.session().id(),
                context.bindingRef(),
                terminationSignal);
    }

    Flux<ChatEvent> continueWithDomainAgent(
            DomainAgentRerouteContext reroute,
            RouteSignalResult signal,
            RouteTarget nextRoute,
            Function<DomainAgentRunContext, Flux<ChatEvent>> continuation) {
        DomainAgentRunContext context = reroute.context();
        recordIntent(context, signal.intentDecision(), nextRoute);
        String currentRouteSource = bindingPolicy.routeSource(context.bindingRef().get());
        if (bindingPolicy.requiresRouteSwitchConfirmation(currentRouteSource)
                && !reroute.currentDomainAgentId().equals(nextRoute.selectedAgentCode())) {
            return Flux.just(eventFactory.routeSwitchConfirmationRequest(
                    reroute,
                    signal,
                    currentRouteSource));
        }
        Mono<?> policyResolution = persistenceGate == null
                ? Mono.just(context.persistenceState())
                : persistenceGate.resolve(
                        context.user(), nextRoute, context.persistenceState(), context.forwardHeaders());
        return policyResolution.then(requireCurrentOwnerRunning(
                context.executionClaim(), "before-domain-agent-reroute-binding"))
                .thenMany(Flux.usingWhen(
                        Mono.fromCallable(() -> prepareDomainAgentReplacement(reroute, signal, nextRoute))
                                .subscribeOn(eventIoScheduler),
                        replacement -> executeDomainAgentReplacement(
                                reroute, signal, nextRoute, continuation, replacement),
                        replacement -> cleanupUnstartedReplacement(
                                context, replacement, "complete"),
                        (replacement, failure) -> cleanupUnstartedReplacement(
                                context, replacement, "error"),
                        replacement -> cleanupUnstartedReplacement(
                                context, replacement, "cancel")));
    }

    private ReplacementBindingLifecycle prepareDomainAgentReplacement(
            DomainAgentRerouteContext reroute,
            RouteSignalResult signal,
            RouteTarget nextRoute) {
        DomainAgentRunContext context = reroute.context();
        context.bindingRef().set(bindingPolicy.markRejected(
                context.bindingRef().get(), reroute.refusal()));
        return new ReplacementBindingLifecycle(bindDomainAgent(reroute, signal, nextRoute));
    }

    private Flux<ChatEvent> executeDomainAgentReplacement(
            DomainAgentRerouteContext reroute,
            RouteSignalResult signal,
            RouteTarget nextRoute,
            Function<DomainAgentRunContext, Flux<ChatEvent>> continuation,
            ReplacementBindingLifecycle replacement) {
        DomainAgentRunContext context = reroute.context();
        return requireCurrentOwnerRunning(
                context.executionClaim(), "before-domain-agent-reroute-runtime")
                .thenMany(Flux.defer(() -> {
                    RuntimeBinding nextBinding = replacement.binding();
                    context.bindingRef().set(nextBinding);
                    context.routeRef().set(nextRoute);
                    appliedRouteRecorder.bindResolvedRouteRequired(
                            context.runId(), nextRoute, nextBinding, context.executionClaim(),
                            context.persistenceState());
                    MemoryContext runtimeMemory = recordAppliedRoute(reroute, signal, nextRoute, nextBinding);
                    ChatCommand runtimeCommand = runtimeCommand(context, nextRoute, signal.intentDecision());
                    DomainAgentRunContext nextContext = nextRunContext(
                            context,
                            reroute,
                            new ReplacementExecution(signal, nextRoute, runtimeMemory, runtimeCommand));
                    Sinks.One<Void> reroutePersisted = Sinks.one();
                    ChatEvent rerouteEvent = new PersistenceAcknowledgedEvent(
                            eventFactory.rerouteMetadata(
                                    context,
                                    reroute.refusal(),
                                    nextRoute,
                                    "AUTO_SWITCH"),
                            reroutePersisted);
                    // 新 Runtime 只能在重路由事件确认落库后订阅，写入拒绝会触发未启动 Binding 补偿。
                    return Flux.concat(
                            Flux.just(rerouteEvent),
                            reroutePersisted.asMono()
                                    .publishOn(eventIoScheduler)
                                    .thenMany(replacementRuntime(continuation, nextContext, replacement)));
                }));
    }

    private Flux<ChatEvent> replacementRuntime(
            Function<DomainAgentRunContext, Flux<ChatEvent>> continuation,
            DomainAgentRunContext context,
            ReplacementBindingLifecycle replacement) {
        return Flux.defer(() -> {
            appliedRouteRecorder.markRuntimeDispatchStartedRequired(
                    context.runId(),
                    context.route(),
                    replacement.binding(),
                    context.executionClaim(),
                    context.persistenceState());
            Flux<ChatEvent> runtimeEvents = continuation.apply(context);
            if (runtimeEvents == null) {
                return Flux.error(new IllegalStateException(
                        "DomainAgent replacement continuation returned null"));
            }
            return runtimeEvents.doOnSubscribe(ignored -> replacement.markRuntimeSubscribed());
        });
    }

    private Mono<Void> cleanupUnstartedReplacement(
            DomainAgentRunContext context,
            ReplacementBindingLifecycle replacement,
            String terminationSignal) {
        if (replacement.runtimeSubscribed()) {
            return Mono.empty();
        }
        Mono<Void> cleanup = Mono.<Void>fromRunnable(() -> {
                    RuntimeBinding binding = replacement.binding();
                    boolean cancelled = runtimeBindingService.cancelActiveForRun(
                            binding, context.runId());
                    if (cancelled) {
                        context.bindingRef().compareAndSet(
                                binding, binding.withStatus(RuntimeBindingStatus.CANCELLED));
                    }
                })
                .subscribeOn(controlIoScheduler);
        int maxAttempts = domainAgentProperties.normalizedBindingCompensationMaxAttempts();
        if (maxAttempts > 1) {
            cleanup = cleanup.retryWhen(Retry.fixedDelay(
                            maxAttempts - 1L,
                            domainAgentProperties.normalizedBindingCompensationRetryBackoff())
                    .filter(RuntimeException.class::isInstance)
                    .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
        }
        return cleanup
                .onErrorResume(ex -> {
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_WRITE_FAILED,
                                    "Unstarted DomainAgent replacement binding cleanup failed")
                            .runId(context.runId())
                            .sessionId(context.session().id())
                            .operation("domain-agent.reroute.binding-cleanup")
                            .attribute("bindingId", replacement.binding().id())
                            .attribute("terminationSignal", terminationSignal)
                            .attribute("maxAttempts", maxAttempts)
                            .build(), ex);
                    return Mono.empty();
                })
                .then();
    }

    private RuntimeBinding bindDomainAgent(DomainAgentRerouteContext reroute,
                                           RouteSignalResult signal,
                                           RouteTarget nextRoute) {
        DomainAgentRunContext context = reroute.context();
        return runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                context.user().tenantId(),
                context.user().ownerUserId(),
                context.session().id(),
                context.runId(),
                bindingPolicy.runtimeBindingLeafId(context.command()),
                nextRoute.selectedAgentCode(),
                nextRoute.routeSource(),
                routeResolutionCoordinator.domainAgentBindingMetadata(nextRoute, signal.intentDecision()),
                reroute.agentMode()));
    }

    private MemoryContext recordAppliedRoute(DomainAgentRerouteContext reroute,
                                             RouteSignalResult signal,
                                             RouteTarget route,
                                             RuntimeBinding binding) {
        DomainAgentRunContext context = reroute.context();
        return appliedRouteRecorder.recordAppliedRouteDecision(
                new AppliedRouteRecorder.AppliedRouteDecision(
                        context.user(),
                        context.session().id(),
                        context.runId(),
                        reroute.intentQuery(),
                        signal.intentDecision(),
                        route,
                        binding,
                        context.memory()));
    }

    private void recordIntent(DomainAgentRunContext context,
                              IntentDecision intent,
                              RouteTarget route) {
        appliedRouteRecorder.recordIntent(
                context.user(),
                context.command(),
                context.runId(),
                intent,
                route,
                0.0,
                null);
    }

    private ChatCommand runtimeCommand(DomainAgentRunContext context,
                                       RouteTarget route,
                                       IntentDecision intent) {
        return routeResolutionCoordinator.runtimeCommand(
                new RouteResolutionCoordinator.RuntimeCommandRequest(
                        context.command(),
                        null,
                        context.documents(),
                        route,
                        intent,
                        null));
    }

    private DomainAgentRunContext nextRunContext(DomainAgentRunContext context,
                                                 DomainAgentRerouteContext reroute,
                                                 ReplacementExecution replacement) {
        return new DomainAgentRunContext(
                replacement.runtimeCommand(),
                context.runId(),
                context.session(),
                replacement.runtimeMemory(),
                replacement.nextRoute(),
                context.user(),
                context.routeRef(),
                context.bindingRef(),
                context.executionClaim(),
                context.forwardHeaders(),
                context.traceContext(),
                replacement.signal().intentDecision(),
                context.documents(),
                reroute.rejectedDomainAgentIds(),
                context.rerouteCount() + 1,
                context.routeMemoryQuery(),
                context.persistenceState(),
                context.pendingInteractionPayloadRef());
    }

    private Mono<Void> requireCurrentOwnerRunning(RunExecutionClaim claim, String stage) {
        return Mono.fromCallable(() -> {
                    if (!chatRunLeaseService.isCurrentOwnerRunning(claim)) {
                        throw new ChatEventAppendRejectedException(
                                "run execution owner 已失效: runId="
                                        + (claim == null ? null : claim.runId()) + ", stage=" + stage);
                    }
                    return true;
                })
                .subscribeOn(eventIoScheduler)
                .then();
    }

    private record ReplacementExecution(
            RouteSignalResult signal,
            RouteTarget nextRoute,
            MemoryContext runtimeMemory,
            ChatCommand runtimeCommand
    ) {
    }

    private record RelayReplacementExecution(
            RouteSignalResult signal,
            RouteTarget nextRoute,
            RuntimeBindingResolution resolution,
            ChatCommand runtimeCommand,
            MemoryContext runtimeMemory
    ) {
    }

    private static final class ReplacementBindingLifecycle {
        private final RuntimeBinding binding;
        private final AtomicBoolean runtimeSubscribed = new AtomicBoolean(false);

        private ReplacementBindingLifecycle(RuntimeBinding binding) {
            this.binding = binding;
        }

        private RuntimeBinding binding() {
            return binding;
        }

        private boolean runtimeSubscribed() {
            return runtimeSubscribed.get();
        }

        private void markRuntimeSubscribed() {
            runtimeSubscribed.set(true);
        }
    }
}
