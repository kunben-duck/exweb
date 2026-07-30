package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionUnavailableException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 使用本机调度器为 AMBIGUOUS_ROUTE 注册一次性自动选择任务。
 */
final class AmbiguousRouteTimeoutScheduler {
    private static final AppLogger log =
            AppLoggerFactory.getLogger(AmbiguousRouteTimeoutScheduler.class);

    private final TaskScheduler taskScheduler;
    private final ObjectProvider<FinanceChatOrchestrator> orchestratorProvider;
    private final Map<String, PendingTask> pendingTasks = new ConcurrentHashMap<>();

    AmbiguousRouteTimeoutScheduler(
            TaskScheduler taskScheduler,
            ObjectProvider<FinanceChatOrchestrator> orchestratorProvider) {
        this.taskScheduler = taskScheduler;
        this.orchestratorProvider = orchestratorProvider;
    }

    void observe(ChatEvent event, InvocationContext context) {
        if (taskScheduler == null || event == null || context == null
                || !"run.waiting_user".equals(event.type())
                || event.payload() == null
                || !AmbiguousRouteSupport.isAmbiguous(event.payload())) {
            return;
        }
        String interactionId = AmbiguousRouteSupport.firstText(
                event.payload().get("interactionId"));
        Instant autoSelectAt = parseInstant(event.payload().get("autoSelectAt"));
        if (interactionId == null || autoSelectAt == null) {
            return;
        }
        PendingTask pending = new PendingTask(context);
        PendingTask previous = pendingTasks.put(interactionId, pending);
        cancelFuture(previous);
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> trigger(interactionId, pending),
                    autoSelectAt);
            if (future == null) {
                pendingTasks.remove(interactionId, pending);
                log.warn("Ambiguous route auto-selection was not accepted by the scheduler. interactionId={}",
                        interactionId);
                return;
            }
            pending.future = future;
        } catch (RuntimeException ex) {
            pendingTasks.remove(interactionId, pending);
            log.warn(SystemErrorLogEntry.builder(
                            SystemErrorCode.TASK_REJECTED,
                            "Ambiguous route auto-selection scheduling failed")
                    .runId(event.runId())
                    .sessionId(event.sessionId())
                    .operation("ambiguous-route.schedule")
                    .attribute("interactionId", interactionId)
                    .build(), ex);
        }
    }

    void cancel(String interactionId) {
        if (interactionId == null || interactionId.isBlank()) {
            return;
        }
        cancelFuture(pendingTasks.remove(interactionId));
    }

    private void trigger(String interactionId, PendingTask pending) {
        if (!pendingTasks.remove(interactionId, pending)) {
            return;
        }
        FinanceChatOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null) {
            log.warn("Ambiguous route auto-selection skipped because orchestrator is unavailable. interactionId={}",
                    interactionId);
            return;
        }
        InvocationContext context = pending.context;
        orchestrator.startAmbiguousRouteTimeout(
                        context.user(),
                        context.traceContext(),
                        interactionId,
                        context.metadata(),
                        context.forwardHeaders())
                .subscribe(
                        ignored -> {
                        },
                        error -> handleTriggerFailure(interactionId, context, error));
    }

    private void handleTriggerFailure(
            String interactionId,
            InvocationContext context,
            Throwable error) {
        if (error instanceof ChatInteractionUnavailableException unavailable
                && "INTERACTION_ALREADY_HANDLED".equals(unavailable.code())) {
            log.debug("Ambiguous route auto-selection lost the Interaction claim race. interactionId={}",
                    interactionId);
            return;
        }
        log.warn(SystemErrorLogEntry.builder(
                        SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                        "Ambiguous route auto-selection failed")
                .traceId(context.traceContext().traceId())
                .operation("ambiguous-route.auto-select")
                .attribute("interactionId", interactionId)
                .build(), error);
    }

    private Instant parseInstant(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private void cancelFuture(PendingTask pending) {
        if (pending != null && pending.future != null) {
            pending.future.cancel(false);
        }
    }

    record InvocationContext(
            UserContext user,
            TraceContext traceContext,
            RuntimeForwardHeaders forwardHeaders,
            Map<String, Object> metadata
    ) {
        InvocationContext {
            traceContext = traceContext == null ? TraceContext.empty() : traceContext;
            forwardHeaders = forwardHeaders == null
                    ? RuntimeForwardHeaders.empty()
                    : forwardHeaders;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    private static final class PendingTask {
        private final InvocationContext context;
        private volatile ScheduledFuture<?> future;

        private PendingTask(InvocationContext context) {
            this.context = context;
        }
    }
}
