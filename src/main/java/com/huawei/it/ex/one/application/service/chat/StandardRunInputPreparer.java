package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.facade.ResolvedChatAttachments;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

import java.util.List;

/** Preserves standard run session, attachment and memory preparation order. */
final class StandardRunInputPreparer {
    private final SessionApplicationService sessionService;
    private final RunMemoryContextAssembler memoryAssembler;
    private final DocumentFacade documentFacade;
    private final ChatInteractionApplicationService interactionService;
    private final ChatRunApplicationService chatRunService;
    private final IdGenerator idGenerator;
    private final ChatRunStartCoordinator runStartCoordinator;
    private final ChatRunAdmissionCoordinator admissionCoordinator;

    StandardRunInputPreparer(SessionApplicationService sessionService,
                             RunMemoryContextAssembler memoryAssembler,
                             DocumentFacade documentFacade,
                             ChatInteractionApplicationService interactionService,
                             ChatRunApplicationService chatRunService,
                             IdGenerator idGenerator,
                             ChatRunStartCoordinator runStartCoordinator,
                             ChatRunAdmissionCoordinator admissionCoordinator) {
        this.sessionService = sessionService;
        this.memoryAssembler = memoryAssembler;
        this.documentFacade = documentFacade;
        this.interactionService = interactionService;
        this.chatRunService = chatRunService;
        this.idGenerator = idGenerator;
        this.runStartCoordinator = runStartCoordinator;
        this.admissionCoordinator = admissionCoordinator;
    }

    PreparedRun prepare(Request request) {
        RunStartAttempt startAttempt = request.startAttempt();
        runStartCoordinator.ensureActive(startAttempt, "before-run-prepare");
        RuntimeForwardHeaders headers = normalizeForwardHeaders(request.forwardHeaders());
        ChatCommand identified = identifiedCommand(request.user(), request.command());
        String explicitDomainAgentId = explicitDomainAgentId(identified);
        boolean directBypass = admissionCoordinator.transactionalAdmissionAvailable()
                && directDomainAgentWaitBypass(identified, explicitDomainAgentId);
        boolean forceReroute = forceReroute(identified);
        if (forceReroute && explicitDomainAgentId != null) {
            throw new IllegalArgumentException(
                    "forceReroute=true 时不能同时指定 targetType/targetId");
        }
        ChatSession session = sessionService.loadOrCreate(identified);
        runStartCoordinator.ensureActive(startAttempt, "after-session-load");
        if (interactionService != null && !directBypass) {
            interactionService.rejectIfWaiting(request.user(), session.id());
        }
        chatRunService.rejectIfActiveRunExists(request.user(), session.id());
        ResolvedChatAttachments resolved = resolveAttachments(request.user(), identified);
        List<AttachmentRef> attachments = resolved.attachments();
        List<UploadedDocument> documents = resolved.documents();
        runStartCoordinator.ensureActive(startAttempt, "after-document-resolve");
        String effectiveMessage = nextMessageWithAttachments(
                identified.runMode(), identified.message(), attachments);
        ChatCommand normalized = normalizedCommand(
                request.user(), session, identified, effectiveMessage, attachments);
        String runId = startAttempt == null
                ? idGenerator.newId(
                        "run",
                        IdGenerateContext.of(
                                request.user().tenantId(),
                                request.user().ownerUserId(),
                                session.id()))
                : startAttempt.runId();
        MemoryContext memory = memoryAssembler.assemble(normalized);
        runStartCoordinator.ensureActive(startAttempt, "after-memory-load");
        return new PreparedRun(
                request.user(),
                request.traceContext(),
                headers,
                normalized,
                session,
                attachments,
                documents,
                memory,
                runId,
                explicitDomainAgentId,
                forceReroute,
                directBypass,
                startAttempt);
    }

    private ResolvedChatAttachments resolveAttachments(
            UserContext user,
            ChatCommand command) {
        List<AttachmentRef> requested = command.attachments() == null
                ? List.of()
                : command.attachments();
        return requested.isEmpty()
                ? ResolvedChatAttachments.empty()
                : documentFacade.resolveChatAttachmentsForUser(user, requested);
    }

    private ChatCommand identifiedCommand(UserContext user, ChatCommand command) {
        return new ChatCommand(
                command.commandId(),
                user.tenantId(),
                user.ownerUserId(),
                command.sessionId(),
                command.conversationId(),
                command.channel(),
                command.message(),
                command.attachments(),
                command.metadata(),
                command.targetType(),
                command.targetId(),
                command.runMode(),
                command.parentMessageId(),
                command.editedMessageId(),
                command.regeneratedMessageId(),
                command.routeTrigger(),
                command.interactionId(),
                command.approved(),
                command.scope(),
                command.questionnaireAnswers(),
                command.appId(),
                command.appName(),
                command.agentMode());
    }

    private ChatCommand normalizedCommand(
            UserContext user,
            ChatSession session,
            ChatCommand identified,
            String effectiveMessage,
            List<AttachmentRef> attachments) {
        return new ChatCommand(
                identified.commandId(),
                user.tenantId(),
                user.ownerUserId(),
                session.id(),
                identified.conversationId(),
                identified.channel(),
                effectiveMessage,
                attachments,
                identified.metadata(),
                identified.targetType(),
                identified.targetId(),
                identified.runMode(),
                identified.parentMessageId(),
                identified.editedMessageId(),
                identified.regeneratedMessageId(),
                identified.routeTrigger(),
                identified.interactionId(),
                identified.approved(),
                identified.scope(),
                identified.questionnaireAnswers(),
                identified.appId(),
                identified.appName(),
                identified.agentMode());
    }

    private String nextMessageWithAttachments(
            ChatRunMode runMode,
            String message,
            List<AttachmentRef> attachments) {
        if (runMode != ChatRunMode.NEXT
                || (message != null && !message.isBlank())) {
            return message;
        }
        return attachments == null || attachments.isEmpty() ? message : "";
    }

    private boolean forceReroute(ChatCommand command) {
        return command != null
                && com.huawei.it.ex.one.application.service.memory.RouteMemoryApplicationService
                        .TRIGGER_USER_CORRECTION.equals(command.routeTrigger());
    }

    private boolean directDomainAgentWaitBypass(
            ChatCommand command,
            String explicitDomainAgentId) {
        return command != null
                && command.runMode() == ChatRunMode.NEXT
                && explicitDomainAgentId != null;
    }

    private String explicitDomainAgentId(ChatCommand command) {
        String targetType = command == null ? null : command.targetType();
        if (targetType == null || targetType.isBlank()) {
            return null;
        }
        if (!"DOMAIN_AGENT".equalsIgnoreCase(targetType)) {
            throw new IllegalArgumentException(
                    "targetType 仅支持 DOMAIN_AGENT，当前值: " + targetType);
        }
        String domainAgentId = command.targetId();
        if (domainAgentId == null || domainAgentId.isBlank()) {
            throw new IllegalArgumentException(
                    "targetType=DOMAIN_AGENT 时 targetId 不能为空");
        }
        return domainAgentId.trim();
    }

    private RuntimeForwardHeaders normalizeForwardHeaders(
            RuntimeForwardHeaders forwardHeaders) {
        return forwardHeaders == null
                ? RuntimeForwardHeaders.empty()
                : forwardHeaders;
    }

    record Request(
            UserContext user,
            TraceContext traceContext,
            ChatCommand command,
            RuntimeForwardHeaders forwardHeaders,
            RunStartAttempt startAttempt
    ) {
    }

    record PreparedRun(
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
