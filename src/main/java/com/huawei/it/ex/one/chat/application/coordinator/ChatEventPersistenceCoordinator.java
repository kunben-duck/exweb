package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.model.RunEventPipelineContext;
import com.huawei.it.ex.one.common.event.ChatEvent;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Keeps the existing event pipeline and single-event commit path behind one application boundary. */
@Component
public class ChatEventPersistenceCoordinator {
    private final ChatEventPipeline eventPipeline;
    private final ChatRunExecutionGateCoordinator executionGateCoordinator;
    private final ChatEventCommitCoordinator eventCommitCoordinator;
    private final DomainAgentRefusalCommitCoordinator refusalCommitCoordinator;

    public ChatEventPersistenceCoordinator(ChatEventPipeline eventPipeline,
                                           ChatRunExecutionGateCoordinator executionGateCoordinator,
                                           ChatEventCommitCoordinator eventCommitCoordinator,
                                           DomainAgentRefusalCommitCoordinator refusalCommitCoordinator) {
        this.eventPipeline = eventPipeline;
        this.executionGateCoordinator = executionGateCoordinator;
        this.eventCommitCoordinator = eventCommitCoordinator;
        this.refusalCommitCoordinator = refusalCommitCoordinator;
    }

    public Flux<ChatEvent> persistAndPublish(Flux<ChatEvent> events,
                                            RunEventPipelineContext context) {
        return eventPipeline.persistAndPublish(events, context,
                event -> persistOneAsync(event, context));
    }

    public Mono<ChatEvent> persistOneAsync(ChatEvent event,
                                           RunEventPipelineContext context) {
        if (refusalCommitCoordinator.applies(event, context)) {
            return refusalCommitCoordinator.commit(event, context);
        }
        return Mono.fromCallable(() -> eventCommitCoordinator.commit(event, context));
    }

    public Flux<ChatEvent> executeAfterRunStarted(RunEventPipelineContext context,
                                                  Supplier<Flux<ChatEvent>> bodySupplier) {
        return executionGateCoordinator.execute(
                context, bodySupplier, event -> persistOneAsync(event, context));
    }

}
