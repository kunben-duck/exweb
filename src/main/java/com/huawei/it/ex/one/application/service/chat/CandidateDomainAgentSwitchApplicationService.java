package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.facade.ResolvedChatAttachments;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.CandidateDomainAgentSwitchCommand;
import com.huawei.it.ex.one.domain.chat.CandidateSwitchConflictException;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessageAttachment;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;

import reactor.core.publisher.Mono;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/** 串行协调候选DomainAgent切换，确保source Run终止后才复用原user消息创建新Run。 */
@Service
public class CandidateDomainAgentSwitchApplicationService {
    private static final String SWITCH_REASON = "CANDIDATE_SWITCH";

    private final ChatRunApplicationService chatRunService;
    private final SessionApplicationService sessionService;
    private final ChatMessageRepository messageRepository;
    private final DocumentFacade documentFacade;
    private final ChatRunStopCoordinator stopCoordinator;
    private final ChatRunStartCoordinator runStartCoordinator;
    private final ChatRunExecutionCoordinator runExecutionCoordinator;

    public CandidateDomainAgentSwitchApplicationService(
            ChatRunApplicationService chatRunService,
            SessionApplicationService sessionService,
            ChatMessageRepository messageRepository,
            DocumentFacade documentFacade,
            ChatRunStopCoordinator stopCoordinator,
            ChatRunStartCoordinator runStartCoordinator,
            ChatRunExecutionCoordinator runExecutionCoordinator) {
        this.chatRunService = chatRunService;
        this.sessionService = sessionService;
        this.messageRepository = messageRepository;
        this.documentFacade = documentFacade;
        this.stopCoordinator = stopCoordinator;
        this.runStartCoordinator = runStartCoordinator;
        this.runExecutionCoordinator = runExecutionCoordinator;
    }

    public Mono<ChatRunStartResult> switchDomainAgent(
            UserContext user,
            TraceContext traceContext,
            CandidateDomainAgentSwitchCommand command,
            RuntimeForwardHeaders forwardHeaders) {
        TraceContext traceSnapshot = traceContext == null ? TraceContext.empty() : traceContext;
        RuntimeForwardHeaders headers = forwardHeaders == null
                ? RuntimeForwardHeaders.empty()
                : forwardHeaders;
        return Mono.defer(() -> {
            CandidateSwitchRunSource source = prepareSource(user, command);
            Mono<Void> stopped = requiresStop(source.sourceRunStatus())
                    ? stopCoordinator.stopRun(
                                    user,
                                    traceSnapshot,
                                    source.sourceRunId(),
                                    SWITCH_REASON,
                                    headers)
                            .then()
                    : Mono.empty();
            return stopped.then(Mono.defer(() -> startReplacement(
                    user, traceSnapshot, command, headers, source)));
        });
    }

    private CandidateSwitchRunSource prepareSource(
            UserContext user,
            CandidateDomainAgentSwitchCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("候选技能切换命令不能为空");
        }
        ChatRun sourceRun = chatRunService.requireOwnedRun(user, command.sourceRunId());
        if (!command.messageId().equals(sourceRun.userMessageId())) {
            throw new IllegalArgumentException("messageId必须与source Run关联的user消息一致");
        }
        ChatSession session = sessionService.getSession(user, sourceRun.sessionId());
        ChatMessage userMessage = messageRepository.findByOwnerAndId(
                        user.tenantId(), user.ownerUserId(), command.messageId())
                .orElseThrow(() -> new SecurityException("消息不存在或不属于当前用户"));
        validateSourceMessage(sourceRun, session, userMessage);
        ensureCurrentSource(session, sourceRun, userMessage.id());
        ResolvedChatAttachments resolved = resolveAttachments(user, userMessage.attachments());
        return new CandidateSwitchRunSource(
                sourceRun.id(),
                sourceRun.status(),
                session,
                userMessage,
                sourceRun.assistantMessageId(),
                resolved);
    }

    private boolean requiresStop(ChatRunStatus status) {
        return status == ChatRunStatus.RUNNING
                || status == ChatRunStatus.CANCELLING
                || status == ChatRunStatus.WAITING_USER;
    }

    private Mono<ChatRunStartResult> startReplacement(
            UserContext user,
            TraceContext traceContext,
            CandidateDomainAgentSwitchCommand command,
            RuntimeForwardHeaders forwardHeaders,
            CandidateSwitchRunSource source) {
        ChatRun latestSource = chatRunService.requireOwnedRun(user, source.sourceRunId());
        if (latestSource.status() == ChatRunStatus.RUNNING
                || latestSource.status() == ChatRunStatus.CANCELLING) {
            throw CandidateSwitchConflictException.stopPending(source.sourceRunId());
        }
        chatRunService.findActiveRun(user, source.session().id()).ifPresent(active -> {
            if (active.id().equals(source.sourceRunId())) {
                throw CandidateSwitchConflictException.stopPending(source.sourceRunId());
            }
            throw CandidateSwitchConflictException.staleSource(source.sourceRunId());
        });
        ChatSession currentSession = sessionService.getSession(user, source.session().id());
        ensureCurrentSource(currentSession, latestSource, source.userMessage().id());
        CandidateSwitchRunSource currentSource = new CandidateSwitchRunSource(
                source.sourceRunId(),
                latestSource.status(),
                currentSession,
                source.userMessage(),
                latestSource.assistantMessageId(),
                source.resolvedAttachments());
        ChatCommand runCommand = replacementCommand(command, currentSource);
        return runStartCoordinator.startStandard(
                user,
                traceContext,
                runCommand,
                startAttempt -> runExecutionCoordinator.executeCandidateSwitch(
                        new ChatRunExecutionCoordinator.Request(
                                user,
                                traceContext,
                                runCommand,
                                forwardHeaders,
                                startAttempt),
                        currentSource));
    }

    private ChatCommand replacementCommand(
            CandidateDomainAgentSwitchCommand command,
            CandidateSwitchRunSource source) {
        ChatSession session = source.session();
        return new ChatCommand(
                null,
                session.tenantId(),
                session.userId(),
                session.id(),
                session.id(),
                session.channel(),
                source.userMessage().content(),
                source.resolvedAttachments().attachments(),
                command.metadata(),
                "DOMAIN_AGENT",
                command.skillId(),
                ChatRunMode.REGENERATE_ASSISTANT,
                null,
                null,
                source.assistantMessageId(),
                null,
                null,
                null,
                null,
                null,
                session.appId(),
                session.appName(),
                command.agentMode(),
                null,
                null,
                command.intentAccessName());
    }

    private void validateSourceMessage(
            ChatRun sourceRun,
            ChatSession session,
            ChatMessage message) {
        if (!"user".equalsIgnoreCase(message.role())) {
            throw new IllegalArgumentException("messageId必须指向user消息");
        }
        if (!session.id().equals(message.sessionId())
                || !session.id().equals(sourceRun.sessionId())) {
            throw new IllegalArgumentException("messageId与source Run不属于同一会话");
        }
        if (message.locked() || message.branchSnapshot()) {
            throw new IllegalArgumentException("分支快照消息不支持候选技能切换");
        }
    }

    private void ensureCurrentSource(
            ChatSession session,
            ChatRun sourceRun,
            String userMessageId) {
        String currentLeaf = session.currentLeafMessageId();
        boolean pointsToUser = userMessageId.equals(currentLeaf);
        boolean pointsToAssistant = sourceRun.assistantMessageId() != null
                && sourceRun.assistantMessageId().equals(currentLeaf);
        if (!pointsToUser && !pointsToAssistant) {
            throw CandidateSwitchConflictException.staleSource(sourceRun.id());
        }
    }

    private ResolvedChatAttachments resolveAttachments(
            UserContext user,
            List<ChatMessageAttachment> persistedAttachments) {
        if (persistedAttachments == null || persistedAttachments.isEmpty()) {
            return ResolvedChatAttachments.empty();
        }
        List<AttachmentRef> requested = persistedAttachments.stream()
                .sorted(Comparator.comparingInt(ChatMessageAttachment::attachmentOrder))
                .map(attachment -> new AttachmentRef(
                        attachment.documentId(),
                        attachment.name(),
                        attachment.contentType(),
                        attachment.sizeBytes()))
                .toList();
        return documentFacade.resolveChatAttachmentsForUser(user, requested);
    }
}
