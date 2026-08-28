package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.agent.MessageSkillContext;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.RunAsyncRunningEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Commits the boundary where a DomainAgent request leaves the live HTTP stream and runs in background. */
@Service
public class DomainAgentAsyncTaskApplicationService {
    private final DomainAgentProperties properties;
    private final ChatRunRepository runRepository;
    private final ChatRunExecutionRepository executionRepository;
    private final SessionApplicationService sessionService;
    private final ChatStreamApplicationService streamService;
    private final ChatInteractionApplicationService interactionService;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final MessageSkillMetadata messageSkillMetadata;

    public DomainAgentAsyncTaskApplicationService(
            DomainAgentProperties properties,
            ChatRunRepository runRepository,
            ChatRunExecutionRepository executionRepository,
            SessionApplicationService sessionService,
            ChatStreamApplicationService streamService,
            ChatInteractionApplicationService interactionService,
            IdGenerator idGenerator,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.runRepository = runRepository;
        this.executionRepository = executionRepository;
        this.sessionService = sessionService;
        this.streamService = streamService;
        this.interactionService = interactionService;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.messageSkillMetadata = new MessageSkillMetadata(objectMapper);
    }

    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public StartResult commitStarted(RunAsyncRunningEvent event, RunEventPipelineContext context) {
        if (!properties.isAsyncTaskEnabled()) {
            throw new IllegalStateException("DomainAgent async task protocol is disabled");
        }
        if (event == null || context == null || context.executionClaim() == null
                || !context.runId().equals(event.runId())) {
            throw new IllegalArgumentException("DomainAgent async task context is invalid");
        }
        ChatRun current = runRepository.findById(context.runId())
                .filter(run -> run.status() == ChatRunStatus.RUNNING)
                .orElseThrow(() -> new IllegalStateException("DomainAgent async run is no longer active"));
        sessionService.lockForMessageMutation(
                context.user().tenantId(), context.user().ownerUserId(), context.session());

        Instant expiresAt = Instant.now().plus(properties.requiredAsyncTaskMaxDuration());
        String assistantMessageId = reusableAssistant(context)
                ? context.continuationInteractionRequest().assistantMessageId()
                : idGenerator.newId("msg", IdGenerateContext.of(
                        context.user().tenantId(), context.user().ownerUserId(),
                        context.session().id(), context.runId()));
        RunAsyncRunningEvent prepared = event.withTaskContext(assistantMessageId, expiresAt);
        ChatEvent stored = streamService.appendWithExecutionGuard(prepared, context.executionClaim());

        ChatMessage assistant = persistAssistant(context, assistantMessageId, expiresAt);
        ChatRun pending = current.withAssistantMessageId(assistant.id())
                .withLastSeq(stored.sequence())
                .withMetadata(DomainAgentAsyncTaskMetadata.runningOverlay(assistant.id(), expiresAt));
        ChatRun savedRun = runRepository.transitionToAsyncWaiting(pending, context.executionClaim());
        if (!executionRepository.markAsyncWaiting(context.executionClaim(), expiresAt)) {
            throw new IllegalStateException("DomainAgent async execution transition was rejected");
        }
        if (reusableAssistant(context)) {
            interactionService.markAnswered(context.continuationInteractionRequest());
        }
        return new StartResult(stored, savedRun, assistant, expiresAt);
    }

    private ChatMessage persistAssistant(
            RunEventPipelineContext context,
            String assistantMessageId,
            Instant expiresAt) {
        ChatMessage existing = reusableAssistant(context)
                ? sessionService.requireAssistantForInternalUpdate(context.session(), assistantMessageId)
                : null;
        String baseMetadata = context.assistant().assistantMetadata(
                existing == null ? null : existing.metadataJson());
        String skillMetadata = messageSkillMetadata.replace(
                baseMetadata, context.assistant().messageSkill().current()).metadataJson();
        String metadata = DomainAgentAsyncTaskMetadata.mergeAssistantMetadata(
                objectMapper, skillMetadata, DomainAgentAsyncTaskMetadata.PHASE_RUNNING, expiresAt);
        String content = context.assistant().shouldPersistMessage()
                ? context.assistant().finalContent()
                : existing == null ? "" : existing.content();
        if (existing == null) {
            return sessionService.saveAssistantMessage(new AssistantMessageSaveCommand(
                    context.user().tenantId(),
                    context.user().ownerUserId(),
                    context.session(),
                    content,
                    context.runId(),
                    context.messagePlan().userMessage().id(),
                    context.messagePlan().regeneratedFromMessageId(),
                    context.assistant().parts(),
                    metadata,
                    assistantMessageId,
                    context.assistant().appendAnswerPart()));
        }
        return sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                context.user().tenantId(),
                context.user().ownerUserId(),
                context.session(),
                assistantMessageId,
                content,
                context.runId(),
                context.assistant().parts(),
                metadata,
                context.assistant().appendAnswerPart()));
    }

    private boolean reusableAssistant(RunEventPipelineContext context) {
        return context.continuationInteractionRequest() != null
                && !InteractionMessageStrategy.newTurn(context.continuationInteractionRequest());
    }

    public record StartResult(
            ChatEvent event,
            ChatRun run,
            ChatMessage assistant,
            Instant expiresAt) {
    }
}
