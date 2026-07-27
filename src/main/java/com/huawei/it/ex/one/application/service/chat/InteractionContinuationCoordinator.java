package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.facade.ResolvedChatAttachments;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatInteractionUnavailableException;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Prepares and claims an Interaction continuation before selecting its execution workflow. */
final class InteractionContinuationCoordinator {
    private final ChatRunStartCoordinator runStartCoordinator;
    private final ChatInteractionApplicationService interactionService;
    private final SessionApplicationService sessionService;
    private final DocumentFacade documentFacade;
    private final IntentClarificationContextAssembler clarificationAssembler;

    InteractionContinuationCoordinator(ChatRunStartCoordinator runStartCoordinator,
                                       ChatInteractionApplicationService interactionService,
                                       SessionApplicationService sessionService,
                                       DocumentFacade documentFacade,
                                       IntentClarificationContextAssembler clarificationAssembler) {
        this.runStartCoordinator = runStartCoordinator;
        this.interactionService = interactionService;
        this.sessionService = sessionService;
        this.documentFacade = documentFacade;
        this.clarificationAssembler = clarificationAssembler;
    }

    Mono<ChatRunStartResult> start(
            UserContext user,
            TraceContext traceContext,
            ChatInteractionResponseCommand command,
            RuntimeForwardHeaders forwardHeaders,
            ContinuationExecution execution) {
        RuntimeForwardHeaders headerSnapshot = forwardHeaders == null
                ? RuntimeForwardHeaders.empty()
                : forwardHeaders;
        ContinuationStartContext context = new ContinuationStartContext(
                user,
                traceContext,
                command,
                headerSnapshot,
                execution,
                new AtomicReference<>());
        return runStartCoordinator.startInteraction(
                user,
                traceContext,
                command.interactionId(),
                (runId, startAttempt) -> executeClaimedContinuation(context, runId, startAttempt));
    }

    ChatInteractionResponseCommand responseCommand(UserContext user, ChatCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("创建 run 请求体不能为空");
        }
        if (command.interactionId() == null) {
            throw new IllegalArgumentException("CONTINUE_INTERACTION 模式 interactionId 不能为空");
        }
        if (hasText(command.message())) {
            throw new IllegalArgumentException("CONTINUE_INTERACTION 模式不支持 message，请使用 questionnaireAnswers/approved/scope");
        }
        if (unsupportedInteractionFields(command)) {
            throw new IllegalArgumentException("CONTINUE_INTERACTION 模式不支持普通 run 路由或消息树字段");
        }
        return new ChatInteractionResponseCommand(
                user,
                command.interactionId(),
                command.approved(),
                command.scope(),
                command.questionnaireAnswers(),
                command.metadata(),
                command.sessionId(),
                command.appId(),
                command.appName(),
                command.attachments(),
                command.agentMode());
    }

    private Flux<ChatEvent> executeClaimedContinuation(ContinuationStartContext context,
                                                       String runId,
                                                       RunStartAttempt startAttempt) {
        return Flux.defer(() -> {
            ChatInteractionClaimResult claim = interactionService.claimPreparedInteractionResponse(
                    context.command(),
                    runId,
                    interaction -> prepareResponse(
                            context.user(),
                            context.command(),
                            interaction,
                            context.inputRef()));
            startAttempt.recordInteraction(claim.request());
            if (startAttempt.aborted()) {
                interactionService.markWaiting(claim.request());
                return Flux.empty();
            }
            try {
                return context.execution().execute(new ContinuationRequest(
                        context.user(),
                        claim,
                        runId,
                        context.forwardHeaders(),
                        context.traceContext(),
                        startAttempt,
                        context.inputRef().get(),
                        context.command().agentMode()));
            } catch (RuntimeException ex) {
                interactionService.markWaiting(claim.request());
                return Flux.error(ex);
            }
        });
    }

    private Map<String, Object> prepareResponse(
            UserContext user,
            ChatInteractionResponseCommand command,
            ChatInteractionRequest interaction,
            AtomicReference<IntentClarificationContextAssembler.ContinuationInput> inputRef) {
        validateSessionContext(user, command, interaction);
        if (interaction.interactionType() != ChatInteractionType.INTENT_CLARIFICATION) {
            if (!command.attachments().isEmpty()) {
                throw new IllegalArgumentException("仅 INTENT_CLARIFICATION 支持在续接时提交附件");
            }
            return interactionService.prepareResponsePayload(command, interaction.interactionType(), null);
        }
        IntentClarificationContextAssembler.ContinuationInput input =
                prepareIntentClarificationInput(user, command, interaction);
        inputRef.set(input);
        Map<String, Object> payload = new LinkedHashMap<>(interactionService.prepareResponsePayload(
                command,
                interaction.interactionType(),
                input.intentQuery()));
        payload.put("answerText", input.messageText());
        return Map.copyOf(payload);
    }

    private IntentClarificationContextAssembler.ContinuationInput prepareIntentClarificationInput(
            UserContext user,
            ChatInteractionResponseCommand command,
            ChatInteractionRequest interaction) {
        List<String> previousDocumentIds = clarificationAssembler.documentIds(interaction.requestPayload());
        LinkedHashMap<String, AttachmentRef> currentRequests = currentAttachmentRequests(command.attachments());
        ResolvedChatAttachments current = resolveCurrent(user, currentRequests);
        ResolvedChatAttachments historical = resolveHistorical(
                user,
                interaction,
                previousDocumentIds,
                currentRequests);
        CumulativeDocuments cumulative = mergeDocuments(
                previousDocumentIds,
                currentRequests,
                current,
                historical);
        List<AttachmentRef> currentAttachments = currentRequests.keySet().stream()
                .map(cumulative.attachmentsById()::get)
                .toList();
        String textAnswer = interactionService.optionalIntentClarificationAnswer(
                command.questionnaireAnswers());
        String messageText = textAnswer == null ? "" : textAnswer;
        String intentQuery = IntentClarificationContextAssembler.answerWithAttachments(
                textAnswer,
                currentAttachments);
        return new IntentClarificationContextAssembler.ContinuationInput(
                messageText,
                intentQuery,
                currentAttachments,
                cumulative.attachments(),
                cumulative.documents(),
                cumulative.documentIds(),
                command.metadata(),
                command.agentMode());
    }

    private LinkedHashMap<String, AttachmentRef> currentAttachmentRequests(
            List<AttachmentRef> attachments) {
        LinkedHashMap<String, AttachmentRef> requests = new LinkedHashMap<>();
        for (AttachmentRef attachment : attachments) {
            if (attachment == null || attachment.documentId() == null || attachment.documentId().isBlank()) {
                throw new IllegalArgumentException("文档 ID 不能为空");
            }
            String documentId = attachment.documentId().trim();
            requests.putIfAbsent(documentId, attachment);
        }
        return requests;
    }

    private ResolvedChatAttachments resolveCurrent(
            UserContext user,
            LinkedHashMap<String, AttachmentRef> currentRequests) {
        return currentRequests.isEmpty()
                ? ResolvedChatAttachments.empty()
                : documentFacade.resolveChatAttachmentsForUser(user, List.copyOf(currentRequests.values()));
    }

    private ResolvedChatAttachments resolveHistorical(
            UserContext user,
            ChatInteractionRequest interaction,
            List<String> previousIds,
            LinkedHashMap<String, AttachmentRef> currentRequests) {
        List<AttachmentRef> requests = previousIds.stream()
                .filter(documentId -> !currentRequests.containsKey(documentId))
                .map(documentId -> new AttachmentRef(documentId, null, null, null))
                .toList();
        try {
            return requests.isEmpty()
                    ? ResolvedChatAttachments.empty()
                    : documentFacade.resolveChatAttachmentsForUser(user, requests);
        } catch (SecurityException | IllegalStateException ex) {
            boolean cancelled = interactionService.cancelWaitingForUnavailableAttachment(interaction);
            if (!cancelled) {
                throw ChatInteractionUnavailableException.alreadyHandled(interaction.id());
            }
            throw ChatInteractionUnavailableException.attachmentUnavailable(interaction.id());
        }
    }

    private CumulativeDocuments mergeDocuments(
            List<String> previousIds,
            LinkedHashMap<String, AttachmentRef> currentRequests,
            ResolvedChatAttachments current,
            ResolvedChatAttachments historical) {
        Map<String, AttachmentRef> attachmentsById = attachmentsByDocumentId(historical.attachments());
        attachmentsById.putAll(attachmentsByDocumentId(current.attachments()));
        Map<String, UploadedDocument> documentsById = documentsById(historical.documents());
        documentsById.putAll(documentsById(current.documents()));
        LinkedHashMap<String, Boolean> order = new LinkedHashMap<>();
        previousIds.forEach(documentId -> order.putIfAbsent(documentId, Boolean.TRUE));
        currentRequests.keySet().forEach(documentId -> order.putIfAbsent(documentId, Boolean.TRUE));
        List<AttachmentRef> attachments = new ArrayList<>();
        List<UploadedDocument> documents = new ArrayList<>();
        for (String documentId : order.keySet()) {
            AttachmentRef attachment = attachmentsById.get(documentId);
            UploadedDocument document = documentsById.get(documentId);
            if (attachment == null || document == null) {
                throw new IllegalStateException("澄清附件解析结果不完整: " + documentId);
            }
            attachments.add(attachment);
            documents.add(document);
        }
        return new CumulativeDocuments(
                attachmentsById,
                List.copyOf(attachments),
                List.copyOf(documents),
                List.copyOf(order.keySet()));
    }

    private Map<String, AttachmentRef> attachmentsByDocumentId(List<AttachmentRef> attachments) {
        Map<String, AttachmentRef> byId = new LinkedHashMap<>();
        if (attachments != null) {
            attachments.stream()
                    .filter(java.util.Objects::nonNull)
                    .forEach(attachment -> byId.putIfAbsent(attachment.documentId(), attachment));
        }
        return byId;
    }

    private Map<String, UploadedDocument> documentsById(List<UploadedDocument> documents) {
        Map<String, UploadedDocument> byId = new LinkedHashMap<>();
        if (documents != null) {
            documents.stream()
                    .filter(java.util.Objects::nonNull)
                    .forEach(document -> byId.putIfAbsent(document.id(), document));
        }
        return byId;
    }

    private void validateSessionContext(UserContext user,
                                        ChatInteractionResponseCommand command,
                                        ChatInteractionRequest interaction) {
        if (hasText(command.sessionId()) && !command.sessionId().equals(interaction.sessionId())) {
            throw new IllegalArgumentException("sessionId 与 Interaction 所属会话不一致");
        }
        if (command.appId() == null && command.appName() == null) {
            return;
        }
        if (!hasText(command.sessionId())) {
            throw new IllegalArgumentException("CONTINUE_INTERACTION 携带 App Tag 时 sessionId 不能为空");
        }
        sessionService.validateAppTag(
                user,
                interaction.sessionId(),
                command.appId(),
                command.appName());
    }

    private boolean unsupportedInteractionFields(ChatCommand command) {
        return command.targetType() != null
                || command.targetId() != null
                || command.parentMessageId() != null
                || command.editedMessageId() != null
                || command.regeneratedMessageId() != null
                || command.routeTrigger() != null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    interface ContinuationExecution {
        Flux<ChatEvent> execute(ContinuationRequest request);
    }

    record ContinuationRequest(
            UserContext user,
            ChatInteractionClaimResult claim,
            String runId,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            RunStartAttempt startAttempt,
            IntentClarificationContextAssembler.ContinuationInput clarificationInput,
            AgentModeProfile agentMode
    ) {
    }

    private record ContinuationStartContext(
            UserContext user,
            TraceContext traceContext,
            ChatInteractionResponseCommand command,
            RuntimeForwardHeaders forwardHeaders,
            ContinuationExecution execution,
            AtomicReference<IntentClarificationContextAssembler.ContinuationInput> inputRef
    ) {
    }

    private record CumulativeDocuments(
            Map<String, AttachmentRef> attachmentsById,
            List<AttachmentRef> attachments,
            List<UploadedDocument> documents,
            List<String> documentIds
    ) {
    }
}
