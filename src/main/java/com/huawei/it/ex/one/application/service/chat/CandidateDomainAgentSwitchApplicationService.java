/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.facade.ResolvedChatAttachments;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.conversation.ChatInteractionRequestRepository;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.CandidateDomainAgentSwitchCommand;
import com.huawei.it.ex.one.domain.chat.CandidateSwitchConflictException;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
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
import java.util.Objects;

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
    private final CandidateSwitchRouteTraceService routeTraceService;
    private final ChatInteractionRequestRepository interactionRepository;

    public CandidateDomainAgentSwitchApplicationService(
            ChatRunApplicationService chatRunService,
            SessionApplicationService sessionService,
            ChatMessageRepository messageRepository,
            DocumentFacade documentFacade,
            ChatRunStopCoordinator stopCoordinator,
            ChatRunStartCoordinator runStartCoordinator,
            ChatRunExecutionCoordinator runExecutionCoordinator,
            CandidateSwitchRouteTraceService routeTraceService,
            ChatInteractionRequestRepository interactionRepository) {
        this.chatRunService = chatRunService;
        this.sessionService = sessionService;
        this.messageRepository = messageRepository;
        this.documentFacade = documentFacade;
        this.stopCoordinator = stopCoordinator;
        this.runStartCoordinator = runStartCoordinator;
        this.runExecutionCoordinator = runExecutionCoordinator;
        this.routeTraceService = routeTraceService;
        this.interactionRepository = interactionRepository;
    }

    /** 先验证可信来源并停止旧 Run，再受理复用原 user 消息的替代 Run；两阶段不共用长事务。 */
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
        AssistantSource assistant = resolveAssistantSource(user, sourceRun);
        String expectedLeaf = resolveHistoricalLeaf(user, session, sourceRun, userMessage.id(), assistant.messageId());
        // 在 Stop 前校验原消息附件，避免因附件无效先停止仍可继续的旧任务。
        ResolvedChatAttachments resolved = resolveAttachments(user, userMessage.attachments());
        return new CandidateSwitchRunSource(
                sourceRun.id(),
                sourceRun.status(),
                session,
                userMessage,
                assistant.messageId(),
                assistant.previousRunId(),
                resolved,
                CandidateSwitchRouteTrace.empty(),
                expectedLeaf);
    }

    private AssistantSource resolveAssistantSource(UserContext user, ChatRun run) {
        if (run.assistantMessageId() != null && !run.assistantMessageId().isBlank()) {
            return new AssistantSource(run.assistantMessageId(), null);
        }
        if (!InteractionMessageStrategy.REUSE_ASSISTANT.name().equals(
                run.metadata().get(InteractionMessageStrategy.METADATA_KEY))
                || !ChatInteractionType.INTENT_CLARIFICATION.name().equals(run.metadata().get("interactionType"))) {
            return new AssistantSource(null, null);
        }
        // 续跑尚未保存结果时直接 assistant 关联为空；只以持久化 Interaction 证明复用关系。
        String interactionId = metadataText(run, "interactionId");
        String assistantId = metadataText(run, "interactionAssistantMessageId");
        if (interactionId == null || assistantId == null) {
            throw CandidateSwitchConflictException.staleSource(run.id());
        }
        ChatInteractionRequest interaction = interactionRepository.findByOwnerAndId(
                        user.tenantId(), user.ownerUserId(), interactionId)
                .orElseThrow(() -> CandidateSwitchConflictException.staleSource(run.id()));
        if (!Objects.equals(user.tenantId(), interaction.tenantId())
                || !Objects.equals(user.ownerUserId(), interaction.userId())
                || !Objects.equals(run.sessionId(), interaction.sessionId())
                || !Objects.equals(interactionId, interaction.id())
                || !Objects.equals(run.id(), interaction.continueRunId())
                || !Objects.equals(run.userMessageId(), interaction.userMessageId())
                || !Objects.equals(assistantId, interaction.assistantMessageId())
                || interaction.interactionType() != ChatInteractionType.INTENT_CLARIFICATION
                || !AmbiguousRouteSupport.isAmbiguous(interaction)
                || interaction.sourceRunId() == null || interaction.sourceRunId().isBlank()) {
            throw CandidateSwitchConflictException.staleSource(run.id());
        }
        return new AssistantSource(assistantId, interaction.sourceRunId());
    }

    private String metadataText(ChatRun run, String key) {
        Object value = run.metadata().get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
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
        // Stop 可与自然完成竞争；必须回读终态和当前路径，后续准入事务还会在 Session 锁内复查。
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
        AssistantSource assistant = latestSource.assistantMessageId() == null
                || latestSource.assistantMessageId().isBlank()
                ? new AssistantSource(source.assistantMessageId(), source.reusedAssistantSourceRunId())
                : new AssistantSource(latestSource.assistantMessageId(), null);
        if (source.historical()) {
            if (requiresStop(latestSource.status())
                    || !Objects.equals(source.expectedCurrentLeafMessageId(), currentSession.currentLeafMessageId())
                    || !Objects.equals(source.assistantMessageId(), assistant.messageId())) {
                throw CandidateSwitchConflictException.staleSource(source.sourceRunId());
            }
        } else {
            ensureCurrentSource(currentSession, latestSource.id(), source.userMessage().id(), assistant.messageId());
        }
        CandidateSwitchRouteTrace routeTrace = routeTraceService.load(user, latestSource, command);
        CandidateSwitchRunSource currentSource = new CandidateSwitchRunSource(
                source.sourceRunId(),
                latestSource.status(),
                currentSession,
                source.userMessage(),
                assistant.messageId(),
                assistant.previousRunId(),
                source.resolvedAttachments(),
                routeTrace,
                source.expectedCurrentLeafMessageId());
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
                command.intentAccessName(),
                null);
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
            String sourceRunId,
            String userMessageId,
            String assistantMessageId) {
        String currentLeaf = session.currentLeafMessageId();
        boolean pointsToUser = userMessageId.equals(currentLeaf);
        boolean pointsToAssistant = assistantMessageId != null
                && assistantMessageId.equals(currentLeaf);
        if (!pointsToUser && !pointsToAssistant) {
            throw CandidateSwitchConflictException.staleSource(sourceRunId);
        }
    }

    private String resolveHistoricalLeaf(UserContext user, ChatSession session, ChatRun sourceRun,
                                         String userMessageId, String assistantMessageId) {
        String leaf = session.currentLeafMessageId();
        if (userMessageId.equals(leaf) || (assistantMessageId != null && assistantMessageId.equals(leaf))) {
            return null;
        }
        // 只允许已结束的历史回答分叉；不能通过历史操作停止另一轮任务或取消其 Interaction。
        if (!sourceRun.status().terminal() || requiresStop(sourceRun.status())
                || assistantMessageId == null || assistantMessageId.isBlank()
                || !messageRepository.isMessageOnPath(
                        user.tenantId(), user.ownerUserId(), session.id(), leaf, assistantMessageId)
                || interactionRepository.hasOpenBySession(user.tenantId(), user.ownerUserId(), session.id())) {
            throw CandidateSwitchConflictException.staleSource(sourceRun.id());
        }
        return leaf;
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

    /** The previous Run is accepted only after validating the persisted Interaction continuation. */
    private record AssistantSource(String messageId, String previousRunId) {}
}
