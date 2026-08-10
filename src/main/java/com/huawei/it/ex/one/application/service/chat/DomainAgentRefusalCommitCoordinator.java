package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.concurrent.atomic.AtomicReference;

/** Commits an automatic DomainAgent refusal and binding cancellation atomically. */
final class DomainAgentRefusalCommitCoordinator {
    private final ChatRunTerminalCommitService terminalCommitService;
    private final DomainAgentRefusalCoordinator refusalCoordinator;
    private final ChatRunCompletionCoordinator completionCoordinator;
    private final ChatRunApplicationService chatRunService;
    private final CommittedChatEventObserver committedEventObserver;
    private final RuntimeBindingCacheSynchronizer cacheSynchronizer;
    private final Scheduler eventIoScheduler;
    private volatile Scheduler controlIoScheduler;

    DomainAgentRefusalCommitCoordinator(ChatRunTerminalCommitService terminalCommitService,
                                        DomainAgentRefusalCoordinator refusalCoordinator,
                                        ChatRunCompletionCoordinator completionCoordinator,
                                        ChatRunApplicationService chatRunService,
                                        CommittedChatEventObserver committedEventObserver,
                                        RuntimeBindingCacheSynchronizer cacheSynchronizer,
                                        Scheduler controlIoScheduler,
                                        Scheduler eventIoScheduler) {
        this.terminalCommitService = terminalCommitService;
        this.refusalCoordinator = refusalCoordinator;
        this.completionCoordinator = completionCoordinator;
        this.chatRunService = chatRunService;
        this.committedEventObserver = committedEventObserver;
        this.cacheSynchronizer = cacheSynchronizer;
        this.controlIoScheduler = controlIoScheduler;
        this.eventIoScheduler = eventIoScheduler;
    }

    void setControlIoScheduler(Scheduler scheduler) {
        if (scheduler != null) {
            this.controlIoScheduler = scheduler;
        }
    }

    boolean applies(ChatEvent event, RunEventPipelineContext context) {
        return refusalCommit(event, context) != null && terminalCommitService != null;
    }

    Mono<ChatEvent> commit(ChatEvent event, RunEventPipelineContext context) {
        AutomaticRefusalCommit automaticRefusal = refusalCommit(event, context);
        AtomicReference<RuntimeBinding> cacheBindingRef = new AtomicReference<>();
        return Mono.fromCallable(() -> terminalCommitService.commitDomainAgentRefusal(
                        new ChatRunTerminalCommitService.DomainAgentRefusalCommitCommand(
                                event,
                                context.executionClaim(),
                                automaticRefusal.binding(),
                                automaticRefusal.refusal().code())))
                .subscribeOn(controlIoScheduler)
                .publishOn(eventIoScheduler)
                .map(result -> {
                    cacheBindingRef.set(result.binding());
                    return publishCommitted(event, result, context);
                })
                .doFinally(ignored -> cacheSynchronizer.schedule(cacheBindingRef.get()));
    }

    private AutomaticRefusalCommit refusalCommit(ChatEvent event,
                                                  RunEventPipelineContext context) {
        DomainAgentRefusal refusal = DomainAgentRefusal.from(event);
        RuntimeBinding binding = context == null ? null : context.bindingRef().get();
        if (refusal == null || binding == null
                || !RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(binding.provider())
                || binding.status() != RuntimeBindingStatus.ACTIVE
                || refusalCoordinator.requiresRouteSwitchConfirmation(
                        refusalCoordinator.routeSource(binding))) {
            return null;
        }
        return new AutomaticRefusalCommit(binding, refusal);
    }

    private ChatEvent publishCommitted(
            ChatEvent sourceEvent,
            ChatRunTerminalCommitService.CommitResult result,
            RunEventPipelineContext context) {
        ChatEvent stored = result.event();
        RuntimeBinding binding = result.binding();
        context.bindingRef().set(binding);
        AssistantAssembly.ObservationResult assistantObservation = context.assistant().observe(stored);
        completionCoordinator.rememberPendingInteractionRequest(stored, context);
        chatRunService.observeEvent(stored);
        committedEventObserver.observeBindingAndPublish(stored, context, binding);
        acknowledgePersistence(sourceEvent);
        if (assistantObservation.essentialOverflow()) {
            throw context.assistant().overflowException(assistantObservation);
        }
        return stored;
    }

    private void acknowledgePersistence(ChatEvent event) {
        if (event instanceof PersistenceAcknowledgedEvent acknowledged) {
            acknowledged.persisted().tryEmitEmpty();
        }
    }

    private record AutomaticRefusalCommit(RuntimeBinding binding, DomainAgentRefusal refusal) {
    }
}
