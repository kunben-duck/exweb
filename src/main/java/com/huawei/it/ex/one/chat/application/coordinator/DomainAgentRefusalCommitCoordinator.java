package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.runtime.application.model.RuntimeProviders;

import com.huawei.it.ex.one.chat.application.model.PersistenceAcknowledgedEvent;
import com.huawei.it.ex.one.chat.application.model.RunEventPipelineContext;
import com.huawei.it.ex.one.chat.application.service.ChatRunApplicationService;
import com.huawei.it.ex.one.chat.application.service.ChatRunTerminalCommitService;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentRefusal;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBindingStatus;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** Commits an automatic DomainAgent refusal and binding cancellation atomically. */
@Component
public class DomainAgentRefusalCommitCoordinator {
    private final ChatRunTerminalCommitService terminalCommitService;
    private final DomainAgentRefusalCoordinator refusalCoordinator;
    private final ChatRunCompletionCoordinator completionCoordinator;
    private final ChatRunApplicationService chatRunService;
    private final CommittedChatEventObserver committedEventObserver;
    private final RuntimeBindingCacheSynchronizer cacheSynchronizer;
    private final Scheduler controlIoScheduler;
    private final Scheduler eventIoScheduler;

    public DomainAgentRefusalCommitCoordinator(
            ChatRunTerminalCommitService terminalCommitService,
            DomainAgentRefusalCoordinator refusalCoordinator,
            ChatRunCompletionCoordinator completionCoordinator,
            ChatRunApplicationService chatRunService,
            CommittedChatEventObserver committedEventObserver,
            RuntimeBindingCacheSynchronizer cacheSynchronizer,
            @Qualifier("domainAgentControlIoScheduler") Scheduler controlIoScheduler,
            @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler) {
        this.terminalCommitService = terminalCommitService;
        this.refusalCoordinator = refusalCoordinator;
        this.completionCoordinator = completionCoordinator;
        this.chatRunService = chatRunService;
        this.committedEventObserver = committedEventObserver;
        this.cacheSynchronizer = cacheSynchronizer;
        this.controlIoScheduler = controlIoScheduler;
        this.eventIoScheduler = eventIoScheduler;
    }

    public boolean applies(ChatEvent event, RunEventPipelineContext context) {
        return refusalCommit(event, context) != null && terminalCommitService != null;
    }

    public Mono<ChatEvent> commit(ChatEvent event, RunEventPipelineContext context) {
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
                || !RuntimeProviders.DOMAIN_AGENT.equals(binding.provider())
                || binding.status() != RuntimeBindingStatus.ACTIVE
                || refusalCoordinator.protectedRouteSource(refusalCoordinator.routeSource(binding))) {
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
        context.assistant().observe(stored);
        completionCoordinator.rememberPendingInteractionRequest(stored, context);
        chatRunService.observeEvent(stored);
        committedEventObserver.observeBindingAndPublish(stored, context, binding);
        acknowledgePersistence(sourceEvent);
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
