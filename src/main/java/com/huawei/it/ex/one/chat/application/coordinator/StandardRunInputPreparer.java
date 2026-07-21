package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.model.ResolvedChatAttachments;
import com.huawei.it.ex.one.chat.application.mapper.ChatIntentMapper;
import com.huawei.it.ex.one.common.id.IdGenerateContext;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.intent.application.service.IntentMemoryService;
import com.huawei.it.ex.one.intent.application.service.RouteMemoryService;
import com.huawei.it.ex.one.chat.application.model.RunStartAttempt;
import com.huawei.it.ex.one.chat.application.service.ChatInteractionApplicationService;
import com.huawei.it.ex.one.chat.application.service.ChatDocumentService;
import com.huawei.it.ex.one.chat.application.service.ChatRunApplicationService;
import com.huawei.it.ex.one.chat.application.service.SessionApplicationService;
import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatRunMode;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import java.util.List;
import org.springframework.stereotype.Component;

/** Preserves the standard run input, session, attachment and memory preparation order. */
@Component
public class StandardRunInputPreparer {
    private final SessionApplicationService sessionService;
    private final IntentMemoryService memoryService;
    private final ChatDocumentService documentService;
    private final ChatInteractionApplicationService interactionService;
    private final ChatRunApplicationService chatRunService;
    private final IdGenerator idGenerator;
    private final ChatRunStartCoordinator runStartCoordinator;

    public StandardRunInputPreparer(SessionApplicationService sessionService,
                                    IntentMemoryService memoryService,
                                    ChatDocumentService documentService,
                                    ChatInteractionApplicationService interactionService,
                                    ChatRunApplicationService chatRunService,
                                    IdGenerator idGenerator,
                                    ChatRunStartCoordinator runStartCoordinator) {
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.documentService = documentService;
        this.interactionService = interactionService;
        this.chatRunService = chatRunService;
        this.idGenerator = idGenerator;
        this.runStartCoordinator = runStartCoordinator;
    }

    public PreparedRun prepare(Request request, boolean admissionServiceAvailable) {
        RunStartAttempt startAttempt = request.startAttempt();
        runStartCoordinator.ensureActive(startAttempt, "before-run-prepare");
        RuntimeForwardHeaders headerSnapshot = normalizeForwardHeaders(request.forwardHeaders());
        ChatCommand identified = identifiedCommand(request.user(), request.command());
        String explicitDomainAgentId = explicitDomainAgentId(identified);
        boolean directDomainAgentWaitBypass = admissionServiceAvailable
                && directDomainAgentWaitBypass(identified, explicitDomainAgentId);
        boolean forceReroute = forceReroute(identified);
        if (forceReroute && explicitDomainAgentId != null) {
            throw new IllegalArgumentException("forceReroute=true 时不能同时指定 targetType/targetId");
        }
        ChatSession session = sessionService.loadOrCreate(identified);
        runStartCoordinator.ensureActive(startAttempt, "after-session-load");
        if (interactionService != null && !directDomainAgentWaitBypass) {
            interactionService.rejectIfWaiting(request.user(), session.id());
        }
        chatRunService.rejectIfActiveRunExists(request.user(), session.id());
        ResolvedChatAttachments resolvedAttachments = resolveAttachments(request.user(), identified);
        List<AttachmentRef> attachments = resolvedAttachments.attachments();
        List<UploadedDocument> documents = resolvedAttachments.documents();
        runStartCoordinator.ensureActive(startAttempt, "after-document-resolve");
        String effectiveMessage = InteractionContinuationCoordinator.nextMessageWithAttachments(
                identified.runMode(), identified.message(), attachments);
        ChatCommand normalized = normalizedCommand(request.user(), session, identified,
                effectiveMessage, attachments);
        String runId = startAttempt == null
                ? idGenerator.newId("run", IdGenerateContext.of(
                        request.user().tenantId(), request.user().ownerUserId(), session.id()))
                : startAttempt.runId();
        MemoryContext memory = memoryService.loadForRun(ChatIntentMapper.toMemoryRequest(normalized));
        runStartCoordinator.ensureActive(startAttempt, "after-memory-load");
        return new PreparedRun(
                request.user(), request.traceContext(), headerSnapshot, normalized, session,
                attachments, documents, memory, runId, explicitDomainAgentId, forceReroute,
                directDomainAgentWaitBypass, startAttempt);
    }

    private ResolvedChatAttachments resolveAttachments(UserContext user, ChatCommand identified) {
        List<AttachmentRef> requestedAttachments = identified.attachments() == null
                ? List.of()
                : identified.attachments();
        return requestedAttachments.isEmpty()
                ? ResolvedChatAttachments.empty()
                : documentService.resolveChatAttachmentsForUser(user, requestedAttachments);
    }

    private ChatCommand identifiedCommand(UserContext user, ChatCommand command) {
        return new ChatCommand(
                command.commandId(), user.tenantId(), user.ownerUserId(), command.sessionId(),
                command.conversationId(), command.channel(), command.message(), command.attachments(),
                command.metadata(), command.targetType(), command.targetId(), command.runMode(),
                command.parentMessageId(), command.editedMessageId(), command.regeneratedMessageId(),
                command.routeTrigger(), command.interactionId(), command.approved(), command.scope(),
                command.questionnaireAnswers(), command.appId(), command.appName());
    }

    private ChatCommand normalizedCommand(UserContext user,
                                          ChatSession session,
                                          ChatCommand identified,
                                          String effectiveMessage,
                                          List<AttachmentRef> attachments) {
        return new ChatCommand(
                identified.commandId(), user.tenantId(), user.ownerUserId(), session.id(),
                identified.conversationId(), identified.channel(), effectiveMessage, attachments,
                identified.metadata(), identified.targetType(), identified.targetId(), identified.runMode(),
                identified.parentMessageId(), identified.editedMessageId(), identified.regeneratedMessageId(),
                identified.routeTrigger(), identified.interactionId(), identified.approved(), identified.scope(),
                identified.questionnaireAnswers(), identified.appId(), identified.appName());
    }

    private boolean forceReroute(ChatCommand command) {
        return command != null
                && RouteMemoryService.TRIGGER_USER_CORRECTION.equals(command.routeTrigger());
    }

    private boolean directDomainAgentWaitBypass(ChatCommand command, String explicitDomainAgentId) {
        return command != null && command.runMode() == ChatRunMode.NEXT && explicitDomainAgentId != null;
    }

    private String explicitDomainAgentId(ChatCommand command) {
        String targetType = command == null ? null : command.targetType();
        if (targetType == null || targetType.isBlank()) {
            return null;
        }
        if (!"DOMAIN_AGENT".equalsIgnoreCase(targetType)) {
            throw new IllegalArgumentException("targetType 仅支持 DOMAIN_AGENT，当前值: " + targetType);
        }
        String domainAgentId = command.targetId();
        if (domainAgentId == null || domainAgentId.isBlank()) {
            throw new IllegalArgumentException("targetType=DOMAIN_AGENT 时 targetId 不能为空");
        }
        return domainAgentId.trim();
    }

    private RuntimeForwardHeaders normalizeForwardHeaders(RuntimeForwardHeaders forwardHeaders) {
        return forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }

    public record Request(
            UserContext user,
            TraceContext traceContext,
            ChatCommand command,
            RuntimeForwardHeaders forwardHeaders,
            RunStartAttempt startAttempt
    ) {
    }

    public record PreparedRun(
            UserContext user,
            TraceContext traceContext,
            RuntimeForwardHeaders forwardHeaders,
            ChatCommand command,
            ChatSession session,
            List<AttachmentRef> attachments,
            List<UploadedDocument> documents,
            MemoryContext memory,
            String runId,
            String explicitDomainAgentId,
            boolean forceReroute,
            boolean directDomainAgentWaitBypass,
            RunStartAttempt startAttempt
    ) {
    }
}
