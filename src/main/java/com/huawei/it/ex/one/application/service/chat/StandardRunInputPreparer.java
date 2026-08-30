/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.facade.ResolvedChatAttachments;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ActiveRunExistsException;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.CandidateSwitchConflictException;
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
        ExplicitRuntimeTarget explicitRuntimeTarget = explicitRuntimeTarget(identified);
        boolean directBypass = admissionCoordinator.transactionalAdmissionAvailable()
                && directRuntimeWaitBypass(identified, explicitRuntimeTarget);
        boolean forceReroute = forceReroute(identified);
        if (forceReroute && explicitRuntimeTarget != null) {
            throw new IllegalArgumentException(
                    "forceReroute=true 时不能同时指定 targetType/targetId");
        }
        ResolvedChatAttachments resolved = null;
        String trustedInitialTitle = null;
        if (shouldResolveAttachmentsBeforeSession(identified)) {
            resolved = resolveAttachments(request.user(), identified);
            runStartCoordinator.ensureActive(startAttempt, "after-document-resolve");
            trustedInitialTitle = attachmentTitle(resolved.attachments());
        }
        ChatSession session = sessionService.loadOrCreate(identified, trustedInitialTitle);
        runStartCoordinator.ensureActive(startAttempt, "after-session-load");
        if (interactionService != null && !directBypass) {
            interactionService.rejectIfWaiting(request.user(), session.id());
        }
        chatRunService.rejectIfActiveRunExists(request.user(), session.id());
        if (resolved == null) {
            resolved = resolveAttachments(request.user(), identified);
            runStartCoordinator.ensureActive(startAttempt, "after-document-resolve");
        }
        List<AttachmentRef> attachments = resolved.attachments();
        List<UploadedDocument> documents = resolved.documents();
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
        SessionApplicationService.ShortTermMemoryPath memoryPath =
                sessionService.resolveShortTermMemoryPath(normalized, session);
        MemoryContext memory = memoryAssembler.assemble(
                normalized, memoryPath.leafMessageId(), memoryPath.emptyPath());
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
                explicitRuntimeTarget,
                forceReroute,
                directBypass,
                startAttempt);
    }

    /**
     * 使用候选切换入口已经校验的可信user消息准备Run-B，不创建新的user节点。
     */
    PreparedRun prepareCandidateSwitch(Request request, CandidateSwitchRunSource source) {
        if (source == null || source.session() == null || source.userMessage() == null
                || source.resolvedAttachments() == null) {
            throw new IllegalArgumentException("候选技能切换source上下文不完整");
        }
        RunStartAttempt startAttempt = request.startAttempt();
        runStartCoordinator.ensureActive(startAttempt, "before-candidate-switch-prepare");
        RuntimeForwardHeaders headers = normalizeForwardHeaders(request.forwardHeaders());
        ChatCommand identified = identifiedCommand(request.user(), request.command());
        ExplicitRuntimeTarget explicitRuntimeTarget = explicitRuntimeTarget(identified);
        if (explicitRuntimeTarget == null || !explicitRuntimeTarget.domainAgent()) {
            throw new IllegalArgumentException("候选技能切换仅支持直连DomainAgent");
        }
        ChatSession session = source.session();
        if (!session.id().equals(identified.sessionId())
                || !session.id().equals(source.userMessage().sessionId())) {
            throw CandidateSwitchConflictException.staleSource(source.sourceRunId());
        }
        try {
            chatRunService.rejectIfActiveRunExists(request.user(), session.id());
        } catch (ActiveRunExistsException ex) {
            throw CandidateSwitchConflictException.staleSource(source.sourceRunId());
        }
        List<AttachmentRef> attachments = source.resolvedAttachments().attachments();
        List<UploadedDocument> documents = source.resolvedAttachments().documents();
        ChatCommand normalized = normalizedCommand(
                request.user(), session, identified, source.userMessage().content(), attachments);
        String runId = startAttempt == null
                ? idGenerator.newId(
                        "run",
                        IdGenerateContext.of(
                                request.user().tenantId(),
                                request.user().ownerUserId(),
                                session.id()))
                : startAttempt.runId();
        String memoryLeaf = source.userMessage().parentMessageId();
        MemoryContext memory = memoryAssembler.assemble(
                normalized,
                memoryLeaf,
                memoryLeaf == null || memoryLeaf.isBlank());
        runStartCoordinator.ensureActive(startAttempt, "after-candidate-switch-memory-load");
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
                explicitRuntimeTarget,
                false,
                true,
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

    private boolean shouldResolveAttachmentsBeforeSession(ChatCommand command) {
        return command != null
                && (command.sessionId() == null || command.sessionId().isBlank())
                && command.runMode() == ChatRunMode.NEXT
                && (command.message() == null || command.message().isBlank())
                && command.attachments() != null
                && !command.attachments().isEmpty();
    }

    static String attachmentTitle(List<AttachmentRef> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        AttachmentRef first = attachments.getFirst();
        if (first == null || first.name() == null || first.name().isBlank()) {
            return null;
        }
        return removeLastExtension(first.name().trim());
    }

    private static String removeLastExtension(String name) {
        int separator = name.lastIndexOf('.');
        return separator > 0 && separator < name.length() - 1
                ? name.substring(0, separator)
                : name;
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
                command.agentMode(),
                command.interactionAction(),
                command.language(),
                command.intentAccessName());
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
                identified.agentMode(),
                identified.interactionAction(),
                identified.language(),
                identified.intentAccessName());
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

    private boolean directRuntimeWaitBypass(
            ChatCommand command,
            ExplicitRuntimeTarget explicitRuntimeTarget) {
        return command != null
                && command.runMode() == ChatRunMode.NEXT
                && explicitRuntimeTarget != null;
    }

    private ExplicitRuntimeTarget explicitRuntimeTarget(ChatCommand command) {
        String targetType = command == null ? null : command.targetType();
        if (targetType == null || targetType.isBlank()) {
            return null;
        }
        ExplicitRuntimeTarget.Type type;
        if ("DOMAIN_AGENT".equalsIgnoreCase(targetType)) {
            type = ExplicitRuntimeTarget.Type.DOMAIN_AGENT;
        } else if ("DOMAIN_EXPERT".equalsIgnoreCase(targetType)) {
            type = ExplicitRuntimeTarget.Type.DOMAIN_EXPERT;
        } else {
            throw new IllegalArgumentException(
                    "targetType 仅支持 DOMAIN_AGENT/DOMAIN_EXPERT，当前值: " + targetType);
        }
        String targetId = command.targetId();
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException(
                    "targetType=" + type.name() + " 时 targetId 不能为空");
        }
        return new ExplicitRuntimeTarget(type, targetId);
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
            ExplicitRuntimeTarget explicitRuntimeTarget,
            boolean forceReroute,
            boolean directRuntimeWaitBypass,
            RunStartAttempt startAttempt
    ) {
    }
}
