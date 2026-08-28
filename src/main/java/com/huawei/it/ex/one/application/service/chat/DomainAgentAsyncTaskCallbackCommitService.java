package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.MessageSkillContext;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;
import com.huawei.it.ex.one.domain.chat.MessageCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Performs one async callback terminal CAS and all related database writes in one transaction. */
@Service
public class DomainAgentAsyncTaskCallbackCommitService {
    private final ChatRunRepository runRepository;
    private final ChatRunExecutionRepository executionRepository;
    private final SessionRepository sessionRepository;
    private final SessionApplicationService sessionService;
    private final ChatStreamApplicationService streamService;
    private final ObjectMapper objectMapper;
    private final AgentDataPersistenceEventPolicy retentionPolicy = new AgentDataPersistenceEventPolicy();

    public DomainAgentAsyncTaskCallbackCommitService(
            ChatRunRepository runRepository,
            ChatRunExecutionRepository executionRepository,
            SessionRepository sessionRepository,
            SessionApplicationService sessionService,
            ChatStreamApplicationService streamService,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.executionRepository = executionRepository;
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.streamService = streamService;
        this.objectMapper = objectMapper;
    }

    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public CommitResult commit(PreparedCallback callback) {
        ChatRun initial = runRepository.findById(callback.runId()).orElse(null);
        if (!DomainAgentAsyncTaskMetadata.isAsyncRunning(initial)
                || initial.status() != ChatRunStatus.RUNNING) {
            return CommitResult.rejected(initial);
        }
        ChatSession session = sessionRepository.findByTenantIdAndUserIdAndId(
                        initial.tenantId(), initial.userId(), initial.sessionId())
                .orElseThrow(() -> new IllegalStateException("DomainAgent async callback session is unavailable"));
        sessionService.lockForMessageMutation(initial.tenantId(), initial.userId(), session);

        Instant finishedAt = Instant.now();
        ChatRunStatus terminalStatus = callback.completed()
                ? ChatRunStatus.COMPLETED : ChatRunStatus.FAILED;
        boolean claimed = runRepository.tryClaimExternalTerminal(
                new ChatRunRepository.ExternalTerminalClaim(
                        initial.id(), initial.tenantId(), initial.userId(), initial.sessionId(),
                        terminalStatus, initial.cancelReason(), finishedAt,
                        ChatRunRepository.ExternalTerminalGuard.ASYNC_CALLBACK,
                        null, null, null, null));
        if (!claimed) {
            return CommitResult.rejected(runRepository.findById(initial.id()).orElse(initial));
        }
        ChatRun claimedRun = runRepository.findById(initial.id())
                .orElseThrow(() -> new IllegalStateException("DomainAgent async callback run reload failed"));
        String assistantMessageId = firstNonBlank(
                DomainAgentAsyncTaskMetadata.assistantMessageId(initial), claimedRun.assistantMessageId());
        ChatMessage existing = sessionService.requireAssistantForInternalUpdate(session, assistantMessageId);
        AgentDataPersistenceState persistenceState =
                AgentDataPersistenceState.fromRunMetadata(initial.metadata(), null);
        AssistantAssembly assembly = new AssistantAssembly(persistenceState);
        assembly.messageSkill().replace(MessageSkillContext.runSkillId(initial.metadata()));
        callback.businessEvents().forEach(assembly::observe);

        List<ChatEvent> ordered = callbackEvents(callback, initial, assistantMessageId);
        List<PublishedEvent> sequenced = sequenceAndPersist(ordered, persistenceState);
        ChatEvent terminal = sequenced.getLast().event();

        updateAssistant(callback, session, initial, existing, assembly);
        ChatRun metadataCleared = claimedRun.withMetadataSnapshot(
                DomainAgentAsyncTaskMetadata.clearRunMetadata(claimedRun.metadata()));
        runRepository.save(metadataCleared);
        ChatRun committedRun = runRepository.finalizeExternalTerminal(
                new ChatRunRepository.ExternalTerminalFinalize(
                        initial.id(), initial.tenantId(), initial.userId(), initial.sessionId(),
                        terminalStatus, terminal.sequence(), initial.cancelReason(), finishedAt));
        executionRepository.markTerminal(initial.id(), callback.completed()
                ? ChatRunExecutionStatus.COMPLETED : ChatRunExecutionStatus.FAILED);
        sessionService.advanceLatestMessageSeq(
                new UserContext(initial.tenantId(), initial.userId(), initial.userId()),
                session,
                terminal.sequence());
        return new CommitResult(true, committedRun, existing.id(), List.copyOf(sequenced));
    }

    private List<ChatEvent> callbackEvents(
            PreparedCallback callback,
            ChatRun run,
            String assistantMessageId) {
        List<ChatEvent> events = new ArrayList<>();
        events.add(new RuntimeEvent(
                run.id(), run.sessionId(), 0L, Instant.now(), "run.async_result_started",
                Map.of(
                        "source", "domain-agent",
                        "sourceType", "agent.async_result_started",
                        "status", "ASYNC_RESULT_STARTED",
                        "assistantMessageId", assistantMessageId,
                        "resultMode", callback.resultMode() == null ? "NONE" : callback.resultMode())));
        events.addAll(callback.businessEvents());
        events.add(MessageCompletedEvent.of(run.id(), run.sessionId(), Map.of(
                "messageReady", true,
                "assistantMessageId", assistantMessageId,
                "feedbackTargetMessageId", assistantMessageId,
                "asyncTask", true)));
        if (callback.completed()) {
            events.add(RunCompletedEvent.of(run.id(), run.sessionId(), Map.of(
                    "status", "COMPLETED",
                    "asyncTask", true,
                    "messageReady", true,
                    "assistantMessageId", assistantMessageId,
                    "feedbackTargetMessageId", assistantMessageId,
                    "resultProvided", !callback.businessEvents().isEmpty())));
        } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", "DOMAIN_AGENT_ASYNC_FAILED");
            payload.put("message", "DomainAgent后台任务执行失败");
            payload.put("asyncTask", true);
            payload.put("messageReady", true);
            payload.put("assistantMessageId", assistantMessageId);
            payload.put("feedbackTargetMessageId", assistantMessageId);
            if (callback.error() != null && !callback.error().isNull()) {
                payload.put("error", objectMapper.convertValue(callback.error(), Object.class));
            }
            events.add(ErrorEvent.of(
                    run.id(), run.sessionId(), "DOMAIN_AGENT_ASYNC_FAILED",
                    "DomainAgent后台任务执行失败", payload));
        }
        return List.copyOf(events);
    }

    private List<PublishedEvent> sequenceAndPersist(
            List<ChatEvent> events,
            AgentDataPersistenceState persistenceState) {
        List<PublishedEvent> result = new ArrayList<>(events.size());
        List<ChatEvent> segment = new ArrayList<>();
        AgentDataPersistenceEventPolicy.EventRetention current = null;
        for (ChatEvent event : events) {
            AgentDataPersistenceEventPolicy.EventRetention next = retentionPolicy.retention(event, persistenceState);
            if (current != null && current != next) {
                appendSegment(segment, current, result);
                segment = new ArrayList<>();
            }
            current = next;
            segment.add(event);
        }
        appendSegment(segment, current, result);
        return List.copyOf(result);
    }

    private void appendSegment(
            List<ChatEvent> segment,
            AgentDataPersistenceEventPolicy.EventRetention retention,
            List<PublishedEvent> result) {
        if (segment == null || segment.isEmpty()) {
            return;
        }
        boolean persisted = retention == AgentDataPersistenceEventPolicy.EventRetention.PERSISTED;
        List<ChatEvent> sequenced = persisted
                ? streamService.appendBatchWithoutPublish(segment)
                : streamService.sequenceLiveBatchWithoutExecutionGuard(segment);
        sequenced.forEach(event -> result.add(new PublishedEvent(event, persisted)));
    }

    private void updateAssistant(
            PreparedCallback callback,
            ChatSession session,
            ChatRun run,
            ChatMessage existing,
            AssistantAssembly assembly) {
        boolean hasFrames = !callback.businessEvents().isEmpty();
        boolean replace = hasFrames && "REPLACE".equals(callback.resultMode());
        if (replace) {
            sessionService.deleteAssistantPartsForRun(session, existing.id(), run.id());
        }
        String content = existing.content();
        if (hasFrames && !assembly.persistenceState().placeholderMode()) {
            content = replace
                    ? assembly.finalContent()
                    : nullToEmpty(existing.content()) + assembly.finalContent();
        }
        String metadata = DomainAgentAsyncTaskMetadata.mergeAssistantMetadata(
                objectMapper,
                existing.metadataJson(),
                callback.completed() ? "COMPLETED" : "FAILED",
                null);
        sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                run.tenantId(), run.userId(), session, existing.id(), content, run.id(),
                hasFrames ? assembly.parts() : List.of(), metadata,
                hasFrames && !assembly.persistenceState().placeholderMode() && assembly.appendAnswerPart()));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    public record PreparedCallback(
            String runId,
            boolean completed,
            String resultMode,
            List<ChatEvent> businessEvents,
            JsonNode error) {
        public PreparedCallback {
            resultMode = resultMode == null ? null : resultMode.trim().toUpperCase(Locale.ROOT);
            businessEvents = businessEvents == null ? List.of() : List.copyOf(businessEvents);
        }
    }

    public record PublishedEvent(ChatEvent event, boolean persisted) {
    }

    public record CommitResult(
            boolean accepted,
            ChatRun run,
            String assistantMessageId,
            List<PublishedEvent> events) {
        private static CommitResult rejected(ChatRun run) {
            return new CommitResult(false, run, null, List.of());
        }
    }
}
