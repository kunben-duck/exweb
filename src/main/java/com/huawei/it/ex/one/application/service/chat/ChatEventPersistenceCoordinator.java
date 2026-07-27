package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/** Keeps event batching, single-event commits and the run start gate behind one boundary. */
final class ChatEventPersistenceCoordinator {
    private final ChatEventPipeline eventPipeline;
    private final ChatRunExecutionGateCoordinator executionGateCoordinator;
    private final ChatEventCommitCoordinator eventCommitCoordinator;
    private final DomainAgentRefusalCommitCoordinator refusalCommitCoordinator;

    ChatEventPersistenceCoordinator(ChatEventPipeline eventPipeline,
                                    ChatRunExecutionGateCoordinator executionGateCoordinator,
                                    ChatEventCommitCoordinator eventCommitCoordinator,
                                    DomainAgentRefusalCommitCoordinator refusalCommitCoordinator) {
        this.eventPipeline = eventPipeline;
        this.executionGateCoordinator = executionGateCoordinator;
        this.eventCommitCoordinator = eventCommitCoordinator;
        this.refusalCommitCoordinator = refusalCommitCoordinator;
    }

    Flux<ChatEvent> persistAndPublish(Flux<ChatEvent> events,
                                     RunEventPipelineContext context) {
        return eventPipeline.persistAndPublish(
                events,
                context,
                event -> persistOneAsync(event, context));
    }

    Mono<ChatEvent> persistOneAsync(ChatEvent event,
                                    RunEventPipelineContext context) {
        if (refusalCommitCoordinator.applies(event, context)) {
            return refusalCommitCoordinator.commit(event, context);
        }
        return Mono.fromCallable(() -> eventCommitCoordinator.commit(event, context));
    }

    Flux<ChatEvent> executeAfterRunStarted(
            RunEventPipelineContext context,
            Supplier<Flux<ChatEvent>> bodySupplier) {
        return executionGateCoordinator.execute(
                context,
                bodySupplier,
                event -> persistOneAsync(event, context));
    }

    Mono<Void> requireCurrentOwnerRunning(RunExecutionClaim claim, String stage) {
        return executionGateCoordinator.requireCurrentOwnerRunning(claim, stage);
    }
}
