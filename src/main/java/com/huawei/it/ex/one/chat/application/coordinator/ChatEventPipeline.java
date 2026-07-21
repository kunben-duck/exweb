package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.runtime.application.model.RuntimeProviders;

import com.huawei.it.ex.one.chat.application.model.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.chat.application.service.ChatDeltaCoalescer;
import com.huawei.it.ex.one.chat.application.service.ChatEventBatcher;
import com.huawei.it.ex.one.chat.application.service.ChatRunApplicationService;
import com.huawei.it.ex.one.chat.application.service.ChatStreamApplicationService;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;
import com.huawei.it.ex.one.chat.application.model.PersistenceAcknowledgedEvent;
import com.huawei.it.ex.one.chat.application.model.RunEventPipelineContext;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ErrorEvent;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentRefusal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Existing ordered persistence, batching and post-commit processing pipeline for one chat run.
 */
@Component
public class ChatEventPipeline {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatEventPipeline.class);
    private static final Set<String> BATCHABLE_RUNTIME_EVENT_TYPES = Set.of(
            "message.delta",
            "message.snapshot",
            "runtime.progress",
            "runtime.metadata",
            "runtime.agent",
            "runtime.thinking",
            "runtime.tool",
            "runtime.reference",
            "runtime.card",
            "runtime.event"
    );

    private final ChatDeltaCoalescer chatDeltaCoalescer;
    private final Scheduler eventIoScheduler;
    private final ChatEventBatcher chatEventBatcher;
    private final ChatRunApplicationService chatRunService;
    private final ChatStreamApplicationService chatStreamService;
    private final RuntimeBindingService runtimeBindingService;
    private final ChatRunCompletionCoordinator completionCoordinator;

    public ChatEventPipeline(ChatDeltaCoalescer chatDeltaCoalescer,
                             @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler,
                             ChatEventBatcher chatEventBatcher,
                             ChatRunApplicationService chatRunService,
                             ChatStreamApplicationService chatStreamService,
                             RuntimeBindingService runtimeBindingService,
                             ChatRunCompletionCoordinator completionCoordinator) {
        this.chatDeltaCoalescer = chatDeltaCoalescer;
        this.eventIoScheduler = eventIoScheduler;
        this.chatEventBatcher = chatEventBatcher;
        this.chatRunService = chatRunService;
        this.chatStreamService = chatStreamService;
        this.runtimeBindingService = runtimeBindingService;
        this.completionCoordinator = completionCoordinator;
    }

    public Flux<ChatEvent> persistAndPublish(Flux<ChatEvent> events,
                                             RunEventPipelineContext context,
                                             Function<ChatEvent, Mono<ChatEvent>> singleEventWriter) {
        AtomicBoolean writeRejected = new AtomicBoolean(false);
        String runId = context.runId();
        String sessionId = context.session().id();
        Flux<ChatEvent> acceptedEvents = acceptedEvents(events, context, writeRejected);
        Flux<ChatEventBatcher.Batch> batches = chatEventBatcher == null
                ? acceptedEvents.map(event -> new ChatEventBatcher.Batch(List.of(event), false))
                : chatEventBatcher.batch(acceptedEvents, event -> batchableRuntimeEvent(event, context));
        BatchPersistenceContext persistenceContext = new BatchPersistenceContext(
                context, singleEventWriter, writeRejected, runId, sessionId);
        return batches
                .publishOn(eventIoScheduler)
                .concatMap(batch -> persistBatch(batch, persistenceContext), 0);
    }

    private Flux<ChatEvent> acceptedEvents(Flux<ChatEvent> events,
                                           RunEventPipelineContext context,
                                           AtomicBoolean writeRejected) {
        String runId = context.runId();
        String sessionId = context.session().id();
        return chatDeltaCoalescer.coalesce(events)
                .publishOn(eventIoScheduler)
                .<ChatEvent>handle((event, sink) -> {
                    if (writeRejected.get()) {
                        sink.complete();
                        return;
                    }
                    if (!eventBelongsToCurrentRun(event, runId, sessionId)) {
                        logMismatchedEvent(event, runId, sessionId);
                        rejectPersistenceAcknowledgement(event, new ChatEventAppendRejectedException(
                                "下游返回的事件身份与当前 run/session 不一致"));
                        sink.next(ErrorEvent.of(runId, sessionId, "RUN_EVENT_IDENTITY_MISMATCH",
                                "下游返回的事件身份与当前 run/session 不一致，已终止本轮回答"));
                        sink.complete();
                        return;
                    }
                    if (!chatRunService.shouldAcceptEvent(event)) {
                        rejectPersistenceAcknowledgement(event, new ChatEventAppendRejectedException(
                                "run 已不再接受事件: runId=" + runId));
                        sink.complete();
                        return;
                    }
                    sink.next(event);
                });
    }

    private Flux<ChatEvent> persistBatch(ChatEventBatcher.Batch batch,
                                         BatchPersistenceContext persistence) {
        if (persistence.writeRejected().get()) {
            rejectPersistenceAcknowledgements(batch.events(), new ChatEventAppendRejectedException(
                    "run 已停止接受后续事件: runId=" + persistence.runId()));
            return Flux.empty();
        }
        return persistEventBatchAsync(batch, persistence.context(), persistence.singleEventWriter())
                .onErrorResume(ChatEventAppendRejectedException.class, ex -> {
                    rejectPersistenceAcknowledgements(batch.events(), ex);
                    persistence.writeRejected().set(true);
                    log.info("Stop chat run event stream after guarded insert rejection. runId={}, reason={}",
                            persistence.runId(), ex.getMessage());
                    return Flux.empty();
                })
                .onErrorResume(CommittedBatchPostProcessingException.class, ex -> {
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                                    "Committed chat event batch post-processing failed")
                            .runId(persistence.runId())
                            .sessionId(persistence.sessionId())
                            .operation("chat-event.batch-post-processing")
                            .build());
                    if (!completionCoordinator.hasTerminalCommitService()) {
                        return Flux.error(ex);
                    }
                    ChatEvent failed = completionCoordinator.commitTerminalFailure(persistence.context(), ex);
                    persistence.writeRejected().set(true);
                    return Flux.just(failed);
                })
                .onErrorResume(RuntimeException.class, ex -> {
                    rejectPersistenceAcknowledgements(batch.events(), ex);
                    if (containsEventType(batch.events(), "run.failed")
                            || !completionCoordinator.hasTerminalCommitService()) {
                        return Flux.error(ex);
                    }
                    ChatEvent failed = completionCoordinator.commitTerminalFailure(persistence.context(), ex);
                    persistence.writeRejected().set(true);
                    return Flux.just(failed);
                });
    }

    private Flux<ChatEvent> persistEventBatchAsync(ChatEventBatcher.Batch batch,
                                                   RunEventPipelineContext context,
                                                   Function<ChatEvent, Mono<ChatEvent>> singleEventWriter) {
        if (batch == null || batch.events().isEmpty()) {
            return Flux.empty();
        }
        if (!batch.databaseBatch()) {
            return singleEventWriter.apply(batch.events().getFirst()).flux();
        }
        return Mono.fromCallable(() -> persistOrdinaryEventBatch(batch.events(), context))
                .subscribeOn(eventIoScheduler)
                .flatMapMany(result -> {
                    Flux<ChatEvent> persisted = Flux.fromIterable(result.events());
                    return result.failure() == null
                            ? persisted
                            : persisted.concatWith(Mono.error(result.failure()));
                });
    }

    private CommittedEventBatch persistOrdinaryEventBatch(List<ChatEvent> events,
                                                           RunEventPipelineContext context) {
        List<ChatEvent> storedEvents = chatStreamService.appendBatchWithExecutionGuard(
                events, context.executionClaim());
        if (storedEvents.size() != events.size()) {
            throw new IllegalStateException("聊天事件批量写入结果数量不一致: expected=" + events.size()
                    + ", actual=" + storedEvents.size());
        }
        events.forEach(this::acknowledgePersistence);
        CommittedBatchPostProcessingException failure = null;
        for (ChatEvent stored : storedEvents) {
            failure = attemptCommittedBatchOperation(failure, "assistant.observe", stored,
                    () -> context.assistant().observe(stored));
            failure = attemptCommittedBatchOperation(failure, "interaction.observe", stored,
                    () -> completionCoordinator.rememberPendingInteractionRequest(stored, context));
            failure = attemptCommittedBatchOperation(failure, "run.observe", stored,
                    () -> chatRunService.observeEvent(stored));
            failure = attemptCommittedBatchOperation(failure, "binding.observe", stored, () ->
                    context.bindingRef().set(runtimeBindingService.observeEvent(context.bindingRef().get(), stored)));
            failure = attemptCommittedBatchOperation(failure, "route-memory.observe", stored,
                    () -> completionCoordinator.recordRouteMemoryAfterCommitted(stored, context));
            failure = attemptCommittedBatchOperation(failure, "stream.publish", stored,
                    () -> chatStreamService.publishPersisted(stored));
        }
        return new CommittedEventBatch(storedEvents, failure);
    }

    private CommittedBatchPostProcessingException attemptCommittedBatchOperation(
            CommittedBatchPostProcessingException failure,
            String operation,
            ChatEvent event,
            Runnable action) {
        try {
            action.run();
            return failure;
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "Committed chat event post-processing step failed")
                    .runId(event.runId())
                    .sessionId(event.sessionId())
                    .operation("chat-event.post-processing")
                    .attribute("sequence", event.sequence())
                    .attribute("eventType", event.type())
                    .attribute("failedOperation", operation)
                    .build());
            if (failure == null) {
                return new CommittedBatchPostProcessingException(event, operation, ex);
            }
            failure.addSuppressed(ex);
            return failure;
        }
    }

    private boolean batchableRuntimeEvent(ChatEvent event, RunEventPipelineContext context) {
        if (event == null || event instanceof PersistenceAcknowledgedEvent
                || !BATCHABLE_RUNTIME_EVENT_TYPES.contains(event.type())
                || DomainAgentRefusal.from(event) != null
                || interactionControlEvent(event)) {
            return false;
        }
        String source = firstText(event.payload() == null ? null : event.payload().get("source"));
        if (source != null) {
            return RuntimeProviders.RELAY.equals(source)
                    || RuntimeProviders.DOMAIN_AGENT.equals(source);
        }
        RuntimeBinding binding = context == null ? null : context.bindingRef().get();
        return binding != null
                && (RuntimeProviders.RELAY.equals(binding.provider())
                || RuntimeProviders.DOMAIN_AGENT.equals(binding.provider()));
    }

    private boolean interactionControlEvent(ChatEvent event) {
        if (event == null || event.payload() == null) {
            return false;
        }
        String sourceType = firstText(event.payload().get("sourceType"));
        if (event.payload().containsKey("interactionId")) {
            return true;
        }
        if (sourceType == null) {
            return false;
        }
        String normalized = sourceType.toLowerCase(java.util.Locale.ROOT);
        return "agent.refusal".equals(normalized)
                || normalized.contains("approval")
                || normalized.contains("clarification")
                || normalized.contains("confirmation")
                || normalized.startsWith("route-switch");
    }

    private void logMismatchedEvent(ChatEvent event, String runId, String sessionId) {
        log.error(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                        "Dropped mismatched chat event before persistence")
                .runId(runId)
                .sessionId(sessionId)
                .operation("chat-event.identity-guard")
                .attribute("actualRunId", event == null ? null : event.runId())
                .attribute("actualSessionId", event == null ? null : event.sessionId())
                .attribute("eventType", event == null ? null : event.type())
                .build());
    }

    private boolean eventBelongsToCurrentRun(ChatEvent event, String runId, String sessionId) {
        return event != null && runId.equals(event.runId()) && sessionId.equals(event.sessionId());
    }

    private boolean containsEventType(List<ChatEvent> events, String eventType) {
        return events != null && events.stream()
                .anyMatch(event -> event != null && eventType.equals(event.type()));
    }

    private String firstText(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private void acknowledgePersistence(ChatEvent event) {
        if (event instanceof PersistenceAcknowledgedEvent acknowledged) {
            acknowledged.persisted().tryEmitEmpty();
        }
    }

    private void rejectPersistenceAcknowledgements(List<ChatEvent> events, Throwable failure) {
        if (events != null) {
            events.forEach(event -> rejectPersistenceAcknowledgement(event, failure));
        }
    }

    private void rejectPersistenceAcknowledgement(ChatEvent event, Throwable failure) {
        if (event instanceof PersistenceAcknowledgedEvent acknowledged) {
            acknowledged.persisted().tryEmitError(failure == null
                    ? new ChatEventAppendRejectedException("拒答控制事件未持久化")
                    : failure);
        }
    }

    private record CommittedEventBatch(
            List<ChatEvent> events,
            CommittedBatchPostProcessingException failure
    ) {
        private CommittedEventBatch {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    private record BatchPersistenceContext(
            RunEventPipelineContext context,
            Function<ChatEvent, Mono<ChatEvent>> singleEventWriter,
            AtomicBoolean writeRejected,
            String runId,
            String sessionId
    ) {
    }

    private static final class CommittedBatchPostProcessingException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private CommittedBatchPostProcessingException(ChatEvent event, String operation, RuntimeException cause) {
            super("聊天事件批次已落库，但提交后处理失败: runId=" + event.runId()
                    + ", sequence=" + event.sequence()
                    + ", type=" + event.type()
                    + ", operation=" + operation, cause);
        }
    }
}
