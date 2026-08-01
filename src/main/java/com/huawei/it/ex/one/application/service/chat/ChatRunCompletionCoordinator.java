package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.domain.chat.RunCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunWaitingUserEvent;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import java.util.LinkedHashMap;
import java.util.Map;

/** Preserves the existing waiting-user, completed and terminal publication sequence. */
final class ChatRunCompletionCoordinator {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunCompletionCoordinator.class);

    private final ChatInteractionApplicationService interactionService;
    private final AgentRuntimeExecutor runtimeExecutor;
    private final IdGenerator idGenerator;
    private final ChatRunTerminalCommitService terminalCommitService;
    private final ChatStreamApplicationService streamService;
    private final RuntimeBindingApplicationService bindingService;
    private final RouteMemoryApplicationService routeMemoryService;
    private final ChatRunFailureMapper failureMapper = new ChatRunFailureMapper();

    ChatRunCompletionCoordinator(ChatInteractionApplicationService interactionService,
                                 AgentRuntimeExecutor runtimeExecutor,
                                 IdGenerator idGenerator,
                                 ChatRunTerminalCommitService terminalCommitService,
                                 ChatStreamApplicationService streamService,
                                 RuntimeBindingApplicationService bindingService,
                                 RouteMemoryApplicationService routeMemoryService) {
        this.interactionService = interactionService;
        this.runtimeExecutor = runtimeExecutor;
        this.idGenerator = idGenerator;
        this.terminalCommitService = terminalCommitService;
        this.streamService = streamService;
        this.bindingService = bindingService;
        this.routeMemoryService = routeMemoryService;
    }

    CompletionPlan prepare(ChatEvent event, RunEventPipelineContext context) {
        CompletionMessageTarget target = completionMessageTarget(event, context);
        ChatInteractionRequest waitingRequest = waitingRequest(event, target, context);
        ChatEvent eventToPersist = waitingRequest == null
                ? withCompletionFeedbackPayload(event, target)
                : withWaitingUserPayload(event, target, waitingRequest);
        return new CompletionPlan(eventToPersist, target, waitingRequest);
    }

    boolean hasTerminalCommitService() {
        return terminalCommitService != null;
    }

    boolean ownerRunTerminal(ChatEvent event) {
        return event != null && ("run.completed".equals(event.type())
                || "run.waiting_user".equals(event.type())
                || "run.failed".equals(event.type())
                || "run.cancelled".equals(event.type()));
    }

    ChatEvent commitWaitingUser(CompletionPlan plan, RunEventPipelineContext context) {
        try {
            ChatRunTerminalCommitService.CommitResult result = terminalCommitService.commitWaitingUser(
                    new ChatRunTerminalCommitService.WaitingUserCommitCommand(
                            plan.eventToPersist(),
                            terminalCommitContext(context),
                            terminalMessageTarget(plan.target()),
                            plan.waitingRequest()
                    ));
            return publishCommitted(result, context);
        } catch (ChatEventAppendRejectedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            return commitTerminalFailure(context, ex);
        }
    }

    ChatEvent commitCompleted(CompletionPlan plan, RunEventPipelineContext context) {
        try {
            ChatRunTerminalCommitService.CommitResult result = terminalCommitService.commitCompleted(
                    new ChatRunTerminalCommitService.CompletedCommitCommand(
                            plan.eventToPersist(),
                            terminalCommitContext(context),
                            terminalMessageTarget(plan.target())
                    ));
            return publishCommitted(result, context);
        } catch (ChatEventAppendRejectedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            return commitTerminalFailure(context, ex);
        }
    }

    ChatEvent commitTerminalOnly(ChatEvent eventToPersist, RunEventPipelineContext context) {
        ChatRunTerminalCommitService.CommitResult result = terminalCommitService.commitTerminalOnly(
                new ChatRunTerminalCommitService.TerminalOnlyCommitCommand(
                        eventToPersist,
                        terminalCommitContext(context)
                ));
        return publishCommitted(result, context);
    }

    ChatEvent commitTerminalFailure(RunEventPipelineContext context, RuntimeException ex) {
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_TRANSACTION_FAILED,
                        "Chat run terminal processing failed; falling back to run.failed")
                .runId(context.runId())
                .sessionId(context.session().id())
                .operation("chat-run.terminal-commit")
                .build(), ex);
        return commitTerminalOnly(failureMapper.toEvent(context.runId(), context.session().id(), ex), context);
    }

    void recordRouteMemoryAfterCommitted(ChatEvent stored, RunEventPipelineContext context) {
        if (routeMemoryService == null || stored == null || context == null) {
            return;
        }
        if ("run.waiting_user".equals(stored.type())) {
            recordIntentClarificationAfterWaiting(stored, context);
        }
    }

    void rememberPendingInteractionRequest(ChatEvent stored, RunEventPipelineContext context) {
        if (!questionnaireApprovalRequest(stored) && !intentClarificationRequest(stored)
                && !routeSwitchConfirmationRequest(stored)) {
            return;
        }
        RuntimeBinding binding = context.bindingRef().get();
        String runtimeProvider = binding == null ? null : binding.provider();
        if (!routeSwitchConfirmationRequest(stored) && !intentClarificationRequest(stored)
                && !runtimeExecutor.supportsWaitingUserResponse(runtimeProvider)) {
            return;
        }
        context.pendingInteractionPayloadRef().compareAndSet(null,
                ChatPayloadMaps.immutableCopy(stored.payload()));
    }

    private ChatEvent publishCommitted(ChatRunTerminalCommitService.CommitResult result,
                                       RunEventPipelineContext context) {
        context.bindingRef().set(result.binding());
        bindingService.synchronizeCache(result.binding());
        streamService.publishPersisted(result.event());
        recordRouteMemoryAfterCommitted(result.event(), context);
        return result.event();
    }

    private void recordIntentClarificationAfterWaiting(ChatEvent stored, RunEventPipelineContext context) {
        Map<String, Object> payload = stored.payload() == null ? Map.of() : stored.payload();
        if (!ChatInteractionType.INTENT_CLARIFICATION.name().equals(String.valueOf(payload.get("interactionType")))) {
            return;
        }
        Map<String, Object> requestPayload = context.pendingInteractionPayloadRef().get();
        if (requestPayload == null || requestPayload.isEmpty()) {
            requestPayload = payload;
        }
        Object interactionId = payload.get("interactionId");
        routeMemoryService.appendClarification(context.user(), context.session().id(), stored.runId(),
                interactionId == null ? null : String.valueOf(interactionId), requestPayload);
    }

    private ChatRunTerminalCommitService.TerminalCommitContext terminalCommitContext(
            RunEventPipelineContext context) {
        return new ChatRunTerminalCommitService.TerminalCommitContext(
                context.user(),
                context.session(),
                context.messagePlan(),
                context.bindingRef(),
                context.assistant(),
                context.runId(),
                context.executionClaim(),
                context.continuationInteractionRequest(),
                context.interactionDispatchState()
        );
    }

    private ChatRunTerminalCommitService.MessageTarget terminalMessageTarget(CompletionMessageTarget target) {
        return new ChatRunTerminalCommitService.MessageTarget(target.messageReady(), target.assistantMessageId());
    }

    private CompletionMessageTarget completionMessageTarget(ChatEvent event, RunEventPipelineContext context) {
        if (event == null || !"run.completed".equals(event.type())) {
            return CompletionMessageTarget.notRunCompleted();
        }
        if (context.continuationInteractionRequest() != null
                && !InteractionMessageStrategy.newTurn(context.continuationInteractionRequest())) {
            return CompletionMessageTarget.ready(context.continuationInteractionRequest().assistantMessageId());
        }
        if (!context.assistant().shouldPersistMessage()) {
            return CompletionMessageTarget.notReady();
        }
        String assistantMessageId = idGenerator.newId("msg",
                IdGenerateContext.of(context.user().tenantId(), context.user().ownerUserId(),
                        context.session().id(), context.runId()));
        return CompletionMessageTarget.ready(assistantMessageId);
    }

    private ChatEvent withCompletionFeedbackPayload(ChatEvent event, CompletionMessageTarget target) {
        if (event == null || !target.runCompleted()) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload() == null ? Map.of() : event.payload());
        payload.put("messageReady", target.messageReady());
        if (target.messageReady()) {
            payload.put("assistantMessageId", target.assistantMessageId());
            payload.put("feedbackTargetMessageId", target.assistantMessageId());
        }
        return new RunCompletedEvent(event.runId(), event.sessionId(), event.sequence(),
                event.createdAt(), java.util.Collections.unmodifiableMap(payload));
    }

    private ChatInteractionRequest waitingRequest(ChatEvent event,
                                                  CompletionMessageTarget target,
                                                  RunEventPipelineContext context) {
        if (interactionService == null || event == null || !"run.completed".equals(event.type())) {
            return null;
        }
        Map<String, Object> requestPayload = context.pendingInteractionPayloadRef().get();
        if (requestPayload == null || !target.messageReady()) {
            return null;
        }
        boolean intentClarification = ChatInteractionType.INTENT_CLARIFICATION.name()
                .equals(String.valueOf(requestPayload.get("interactionType")));
        Map<String, Object> interactionRequestPayload = interactionRequestPayload(
                requestPayload, context, intentClarification);
        RuntimeBinding binding = waitingRuntimeBinding(context, intentClarification);
        String runtimeProvider = intentClarification ? "intent-agent" : binding.provider();
        String runtimeSessionId = runtimeSessionId(requestPayload, binding);
        return interactionService.prepareInteraction(new ChatInteractionCreateContext(
                context.user(),
                context.session(),
                context.runId(),
                context.messagePlan().userMessage(),
                target.assistantMessageId(),
                runtimeProvider,
                intentClarification || binding == null ? null : binding.id(),
                runtimeSessionId,
                interactionRequestPayload
        ));
    }

    private Map<String, Object> interactionRequestPayload(Map<String, Object> requestPayload,
                                                          RunEventPipelineContext context,
                                                          boolean intentClarification) {
        if (!intentClarification) {
            return requestPayload;
        }
        Map<String, Object> internalPayload = new LinkedHashMap<>(requestPayload);
        internalPayload.put(IntentClarificationContextAssembler.DOCUMENT_IDS,
                context.intentClarificationDocumentIds());
        return ChatPayloadMaps.immutableCopy(internalPayload);
    }

    private RuntimeBinding waitingRuntimeBinding(RunEventPipelineContext context, boolean intentClarification) {
        RuntimeBinding binding = context.bindingRef().get();
        if (!intentClarification && (binding == null || binding.id() == null || binding.id().isBlank())) {
            throw new IllegalStateException("Interaction 等待态缺少 RuntimeBinding，无法续接 Runtime");
        }
        return binding;
    }

    private ChatEvent withWaitingUserPayload(ChatEvent event,
                                             CompletionMessageTarget target,
                                             ChatInteractionRequest waitingRequest) {
        Map<String, Object> payload = new LinkedHashMap<>(event.payload() == null ? Map.of() : event.payload());
        payload.put("status", "WAITING_USER");
        payload.put("interactionType", waitingRequest.interactionType().name());
        payload.put("interactionId", waitingRequest.id());
        payload.put("messageReady", target.messageReady());
        payload.put("assistantMessageId", target.assistantMessageId());
        payload.put("feedbackTargetMessageId", target.assistantMessageId());
        if (waitingRequest.expiresAt() != null) {
            payload.put("expiresAt", waitingRequest.expiresAt().toString());
        }
        copyIfPresent(waitingRequest.requestPayload(), payload,
                "currentProvider", "currentTargetId", "currentRouteSource",
                "candidateProvider", "candidateTargetId", "candidateRouteSource",
                "candidateIntentCode", "candidateIntentName", "routeAction",
                "refusalCode", "refusalReasonCode", "refusalRecoverable", "refusalReason",
                "intentSessionId", "intentRequestId", "originalQuery",
                "clarificationType", "candidateIntents", "actions",
                "autoSelectAt", "autoSelectTimeoutMs",
                "autoActionAt", "autoActionTimeoutMs", "autoActionType");
        return new RunWaitingUserEvent(event.runId(), event.sessionId(), event.sequence(),
                event.createdAt(), ChatPayloadMaps.immutableCopy(payload));
    }

    private void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String... keys) {
        if (from == null || to == null || keys == null) {
            return;
        }
        for (String key : keys) {
            Object value = from.get(key);
            if (value != null) {
                to.put(key, value);
            }
        }
    }

    private boolean questionnaireApprovalRequest(ChatEvent event) {
        if (event == null || !"runtime.card".equals(event.type()) || event.payload() == null) {
            return false;
        }
        return "approval-request".equals(String.valueOf(event.payload().get("sourceType")))
                && "questionnaire".equalsIgnoreCase(String.valueOf(event.payload().get("operation_type")));
    }

    private boolean routeSwitchConfirmationRequest(ChatEvent event) {
        return event != null && "runtime.card".equals(event.type()) && event.payload() != null
                && "route-switch-confirmation-request".equals(
                String.valueOf(event.payload().get("sourceType")));
    }

    private boolean intentClarificationRequest(ChatEvent event) {
        return event != null && "runtime.card".equals(event.type()) && event.payload() != null
                && "intent-clarification-request".equals(String.valueOf(event.payload().get("sourceType")));
    }

    private String runtimeSessionId(Map<String, Object> payload, RuntimeBinding binding) {
        Object fromPayload = payload == null ? null : payload.get("runtimeSessionId");
        if (fromPayload != null && !String.valueOf(fromPayload).isBlank()) {
            return String.valueOf(fromPayload);
        }
        Object intentSessionId = payload == null ? null : payload.get("intentSessionId");
        if (intentSessionId != null && !String.valueOf(intentSessionId).isBlank()) {
            return String.valueOf(intentSessionId);
        }
        return binding == null ? null : binding.runtimeSessionId();
    }

    record CompletionPlan(
            ChatEvent eventToPersist,
            CompletionMessageTarget target,
            ChatInteractionRequest waitingRequest
    ) {
    }

    record CompletionMessageTarget(
            boolean runCompleted,
            boolean messageReady,
            String assistantMessageId
    ) {
        private static CompletionMessageTarget notRunCompleted() {
            return new CompletionMessageTarget(false, false, null);
        }

        private static CompletionMessageTarget notReady() {
            return new CompletionMessageTarget(true, false, null);
        }

        private static CompletionMessageTarget ready(String assistantMessageId) {
            return new CompletionMessageTarget(true, true, assistantMessageId);
        }
    }
}
