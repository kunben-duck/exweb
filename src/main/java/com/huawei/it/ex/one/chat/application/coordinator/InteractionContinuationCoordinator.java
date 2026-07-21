package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.model.ResolvedChatAttachments;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.chat.application.service.ChatInteractionApplicationService;
import com.huawei.it.ex.one.chat.application.service.ChatDocumentService;
import com.huawei.it.ex.one.chat.application.service.ChatInteractionClaimResult;
import com.huawei.it.ex.one.chat.application.service.ChatInteractionResponseCommand;
import com.huawei.it.ex.one.chat.application.service.SessionApplicationService;
import com.huawei.it.ex.one.chat.application.model.IntentClarificationContinuationInput;
import com.huawei.it.ex.one.chat.application.model.IntentClarificationDocuments;
import com.huawei.it.ex.one.chat.application.model.InteractionContinuationExecutionRequest;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatInteractionType;
import com.huawei.it.ex.one.chat.domain.ChatInteractionUnavailableException;
import com.huawei.it.ex.one.chat.domain.ChatRunMode;
import com.huawei.it.ex.one.chat.domain.ChatRunStartResult;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Coordinates Interaction claim preparation without owning the continuation state machine. */
@Component
public class InteractionContinuationCoordinator {
    private final ChatRunStartCoordinator runStartCoordinator;
    private final ChatInteractionApplicationService interactionService;
    private final SessionApplicationService sessionService;
    private final ChatDocumentService documentService;

    public InteractionContinuationCoordinator(
            ChatRunStartCoordinator runStartCoordinator,
            ChatInteractionApplicationService interactionService,
            SessionApplicationService sessionService,
            ChatDocumentService documentService) {
        this.runStartCoordinator = runStartCoordinator;
        this.interactionService = interactionService;
        this.sessionService = sessionService;
        this.documentService = documentService;
    }

    public Mono<ChatRunStartResult> start(
            UserContext user,
            TraceContext traceContext,
            ChatInteractionResponseCommand command,
            RuntimeForwardHeaders forwardHeaders,
            ContinuationExecution execution) {
        return Mono.defer(() -> {
            RuntimeForwardHeaders headerSnapshot = forwardHeaders == null
                    ? RuntimeForwardHeaders.empty()
                    : forwardHeaders;
            AtomicReference<IntentClarificationContinuationInput> clarificationInputRef = new AtomicReference<>();
            ContinuationStartContext startContext = new ContinuationStartContext(
                    user, traceContext, command, headerSnapshot, clarificationInputRef, execution);
            return runStartCoordinator.startInteraction(
                    user,
                    traceContext,
                    command.interactionId(),
                    (runId, startAttempt) -> executeClaimedContinuation(startContext, runId, startAttempt));
        });
    }

    private Flux<ChatEvent> executeClaimedContinuation(
            ContinuationStartContext context,
            String runId,
            com.huawei.it.ex.one.chat.application.model.RunStartAttempt startAttempt) {
        return Flux.defer(() -> {
            ChatInteractionClaimResult claim = interactionService.claimPreparedInteractionResponse(
                    context.command(), runId, interaction -> prepareResponse(
                            context.user(), context.command(), interaction, context.clarificationInputRef()));
            startAttempt.recordInteraction(claim.request());
            if (startAttempt.aborted()) {
                interactionService.markWaiting(claim.request());
                return Flux.empty();
            }
            try {
                return context.execution().execute(new InteractionContinuationExecutionRequest(
                        context.user(), claim, runId, context.forwardHeaders(), context.traceContext(),
                        startAttempt, context.clarificationInputRef().get()));
            } catch (RuntimeException ex) {
                interactionService.markWaiting(claim.request());
                return Flux.error(ex);
            }
        });
    }

    public ChatInteractionResponseCommand responseCommand(UserContext user, ChatCommand command) {
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
        return new ChatInteractionResponseCommand(user, command.interactionId(), command.approved(), command.scope(),
                command.questionnaireAnswers(), command.metadata(), command.sessionId(), command.appId(), command.appName(),
                command.attachments());
    }

    private boolean unsupportedInteractionFields(ChatCommand command) {
        return command.targetType() != null || command.targetId() != null
                || command.parentMessageId() != null || command.editedMessageId() != null
                || command.regeneratedMessageId() != null || command.routeTrigger() != null;
    }

    private Map<String, Object> prepareResponse(
            UserContext user,
            ChatInteractionResponseCommand command,
            ChatInteractionRequest interaction,
            AtomicReference<IntentClarificationContinuationInput> clarificationInputRef) {
        validateSessionContext(user, command, interaction);
        if (interaction.interactionType() != ChatInteractionType.INTENT_CLARIFICATION) {
            if (!command.attachments().isEmpty()) {
                throw new IllegalArgumentException("仅 INTENT_CLARIFICATION 支持在续接时提交附件");
            }
            return interactionService.prepareResponsePayload(command, interaction.interactionType(), null);
        }
        IntentClarificationContinuationInput input = prepareIntentClarificationInput(user, command, interaction);
        clarificationInputRef.set(input);
        Map<String, Object> payload = new LinkedHashMap<>(interactionService.prepareResponsePayload(
                command, interaction.interactionType(), input.intentQuery()));
        payload.put("answerText", input.messageText());
        return Map.copyOf(payload);
    }

    private IntentClarificationContinuationInput prepareIntentClarificationInput(
            UserContext user,
            ChatInteractionResponseCommand command,
            ChatInteractionRequest interaction) {
        List<String> previousIds = IntentClarificationDocuments.fromPayload(interaction.requestPayload());
        LinkedHashMap<String, AttachmentRef> currentRequests = currentAttachmentRequests(command.attachments());
        ResolvedChatAttachments current = resolveCurrent(user, currentRequests);
        ResolvedChatAttachments historical = resolveHistorical(
                user, interaction, previousIds, currentRequests);
        CumulativeDocuments cumulative = mergeDocuments(previousIds, currentRequests, current, historical);
        List<AttachmentRef> currentAttachments = currentRequests.keySet().stream()
                .map(cumulative.attachmentsById()::get)
                .toList();
        String textAnswer = interactionService.optionalIntentClarificationAnswer(command.questionnaireAnswers());
        String messageText = textAnswer == null ? "" : textAnswer;
        String intentQuery = answerWithAttachments(textAnswer, currentAttachments);
        return new IntentClarificationContinuationInput(
                messageText, intentQuery, currentAttachments,
                cumulative.attachments(), cumulative.documents(), cumulative.documentIds(), command.metadata());
    }

    private LinkedHashMap<String, AttachmentRef> currentAttachmentRequests(List<AttachmentRef> attachments) {
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
                : documentService.resolveChatAttachmentsForUser(user, List.copyOf(currentRequests.values()));
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
                    : documentService.resolveChatAttachmentsForUser(user, requests);
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
            addResolvedDocument(documentId, attachmentsById, documentsById, attachments, documents);
        }
        return new CumulativeDocuments(
                attachmentsById, List.copyOf(attachments), List.copyOf(documents), List.copyOf(order.keySet()));
    }

    private void addResolvedDocument(
            String documentId,
            Map<String, AttachmentRef> attachmentsById,
            Map<String, UploadedDocument> documentsById,
            List<AttachmentRef> attachments,
            List<UploadedDocument> documents) {
        AttachmentRef attachment = attachmentsById.get(documentId);
        UploadedDocument document = documentsById.get(documentId);
        if (attachment == null || document == null) {
            throw new IllegalStateException("澄清附件解析结果不完整: " + documentId);
        }
        attachments.add(attachment);
        documents.add(document);
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

    public static String answerWithAttachments(String answerText, List<AttachmentRef> attachments) {
        String normalizedAnswer = answerText == null || answerText.isBlank() ? null : answerText.trim();
        String fileText = uploadedDocumentText(attachments);
        if (normalizedAnswer == null) {
            return fileText;
        }
        return fileText == null ? normalizedAnswer : normalizedAnswer + " " + fileText;
    }

    public static String nextMessageWithAttachments(
            ChatRunMode runMode,
            String message,
            List<AttachmentRef> attachments) {
        if (runMode != ChatRunMode.NEXT || (message != null && !message.isBlank())) {
            return message;
        }
        return attachments == null || attachments.isEmpty() ? message : "";
    }

    private static String uploadedDocumentText(List<AttachmentRef> attachments) {
        return attachments == null || attachments.isEmpty()
                ? null
                : "[用户上传文档] " + attachments.stream()
                .map(AttachmentRef::name)
                .collect(Collectors.joining("，"));
    }

    private void validateSessionContext(
            UserContext user,
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
        sessionService.validateAppTag(user, interaction.sessionId(), command.appId(), command.appName());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    public interface ContinuationExecution {
        Flux<ChatEvent> execute(InteractionContinuationExecutionRequest request);
    }

    private record ContinuationStartContext(
            UserContext user,
            TraceContext traceContext,
            ChatInteractionResponseCommand command,
            RuntimeForwardHeaders forwardHeaders,
            AtomicReference<IntentClarificationContinuationInput> clarificationInputRef,
            ContinuationExecution execution
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
