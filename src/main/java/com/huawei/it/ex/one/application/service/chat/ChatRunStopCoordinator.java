package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStopDecision;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunCancelledEvent;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Run 取消协调器。
 *
 * <p>stop 接口和删除会话都会经过这里收敛运行态：写 cancel flag、尽力取消下游 Runtime、
 * 取消本机订阅、固化用户可见 partial assistant，并发布标准 {@code run.cancelled}。
 * 下游取消永远是 best-effort，不能拖住 Servlet 删除或 stop 请求。</p>
 */
@Service
public class ChatRunStopCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ChatRunStopCoordinator.class);
    private static final String INTERACTION_ID_METADATA = "interactionId";
    private static final String INTERACTION_ASSISTANT_MESSAGE_ID_METADATA = "interactionAssistantMessageId";
    private static final String USER_STOP_PARTIAL_ASSISTANT_METADATA =
            "{\"partial\":true,\"finishReason\":\"USER_STOP\",\"runStatus\":\"CANCELLED\"}";
    private static final String SESSION_DELETE_PARTIAL_ASSISTANT_METADATA =
            "{\"partial\":true,\"finishReason\":\"SESSION_DELETE\",\"runStatus\":\"CANCELLED\"}";

    private final SessionApplicationService sessionService;
    private final ChatStreamApplicationService chatStreamService;
    private final ChatRunApplicationService chatRunService;
    private final ChatRunLeaseApplicationService chatRunLeaseService;
    private final LocalChatRunExecutionRegistry runExecutionRegistry;
    private final AgentRuntimeExecutor agentRuntimeExecutor;
    private final ChatInteractionApplicationService chatInteractionService;
    private final ChatRunTerminalCommitService terminalCommitService;
    private final IdGenerator idGenerator;

    @Autowired
    public ChatRunStopCoordinator(SessionApplicationService sessionService,
                                  ChatStreamApplicationService chatStreamService,
                                  ChatRunApplicationService chatRunService,
                                  ChatRunLeaseApplicationService chatRunLeaseService,
                                  LocalChatRunExecutionRegistry runExecutionRegistry,
                                  AgentRuntimeExecutor agentRuntimeExecutor,
                                  ChatInteractionApplicationService chatInteractionService,
                                  ChatRunTerminalCommitService terminalCommitService,
                                  IdGenerator idGenerator) {
        this.sessionService = sessionService;
        this.chatStreamService = chatStreamService;
        this.chatRunService = chatRunService;
        this.chatRunLeaseService = chatRunLeaseService;
        this.runExecutionRegistry = runExecutionRegistry;
        this.agentRuntimeExecutor = agentRuntimeExecutor;
        this.chatInteractionService = chatInteractionService;
        this.terminalCommitService = terminalCommitService;
        this.idGenerator = idGenerator;
    }

    public ChatRunStopCoordinator(SessionApplicationService sessionService,
                                  ChatStreamApplicationService chatStreamService,
                                  ChatRunApplicationService chatRunService,
                                  ChatRunLeaseApplicationService chatRunLeaseService,
                                  LocalChatRunExecutionRegistry runExecutionRegistry,
                                  AgentRuntimeExecutor agentRuntimeExecutor,
                                  ChatInteractionApplicationService chatInteractionService,
                                  IdGenerator idGenerator) {
        this(sessionService, chatStreamService, chatRunService, chatRunLeaseService,
                runExecutionRegistry, agentRuntimeExecutor, chatInteractionService, null, idGenerator);
    }

    public ChatRunStopCoordinator(SessionApplicationService sessionService,
                                  ChatStreamApplicationService chatStreamService,
                                  ChatRunApplicationService chatRunService,
                                  ChatRunLeaseApplicationService chatRunLeaseService,
                                  LocalChatRunExecutionRegistry runExecutionRegistry,
                                  AgentRuntimeExecutor agentRuntimeExecutor,
                                  IdGenerator idGenerator) {
        this(sessionService, chatStreamService, chatRunService, chatRunLeaseService,
                runExecutionRegistry, agentRuntimeExecutor, null, null, idGenerator);
    }

    public ChatRunStopCoordinator(SessionApplicationService sessionService,
                                  ChatStreamApplicationService chatStreamService,
                                  ChatRunApplicationService chatRunService,
                                  ChatRunLeaseApplicationService chatRunLeaseService,
                                  LocalChatRunExecutionRegistry runExecutionRegistry,
                                  AgentRuntimeExecutor agentRuntimeExecutor,
                                  com.huawei.it.ex.one.application.service.runtime.DomainAgentExecutor ignoredDomainAgentExecutor,
                                  IdGenerator idGenerator) {
        this(sessionService, chatStreamService, chatRunService, chatRunLeaseService,
                runExecutionRegistry, agentRuntimeExecutor, null, null, idGenerator);
    }

    public Mono<ChatRunStopResult> stopRun(UserContext user, String runId, String reason,
                                           RuntimeForwardHeaders forwardHeaders) {
        return Mono.defer(() -> Mono.just(stopRunNow(user, runId, reason, forwardHeaders)));
    }

    public ChatRunStopResult stopRunNow(UserContext user, String runId, String reason,
                                        RuntimeForwardHeaders forwardHeaders) {
        return stopRunNow(user, runId, reason, forwardHeaders, null);
    }

    private ChatRunStopResult stopRunNow(UserContext user, String runId, String reason,
                                         RuntimeForwardHeaders forwardHeaders, ChatSession sessionSnapshot) {
        String effectiveReason = normalizeReason(reason);
        RuntimeForwardHeaders headerSnapshot = normalizeForwardHeaders(forwardHeaders);
        ChatRunStopDecision decision = chatRunService.requestStop(user, runId, effectiveReason);
        ChatRun run = decision.run();
        if (!decision.appendCancelledEvent()) {
            reconcileTerminalInteraction(run);
            return chatRunService.toStopResult(run);
        }
        /*
         * 先通知下游，再 dispose 本机订阅。Relay WebSocket stop 需要命中仍存活的
         * outbound exchange；取消正确性已由 requestStop 写入的 cancel flag 和 guarded insert 保证。
         */
        cancelDownstreamBestEffort(run, user, headerSnapshot);
        runExecutionRegistry.cancel(run.id());
        if (!chatRunService.shouldAcceptEvent(RunCancelledEvent.of(run.id(), run.sessionId(), run.cancelReason()))) {
            ChatRun latest = chatRunService.requireOwnedRun(user, run.id());
            reconcileTerminalInteraction(latest);
            return chatRunService.toStopResult(latest);
        }
        StopMessageTarget messageTarget = preparePartialAssistant(user, run, effectiveReason, sessionSnapshot);
        ChatRun latest;
        if (terminalCommitService == null) {
            messageTarget = persistPreparedPartialAssistant(run, messageTarget);
            ChatEvent cancelEvent = RunCancelledEvent.of(run.id(), run.sessionId(), run.cancelReason(),
                    messageTarget.messageReady(), messageTarget.assistantMessageId());
            ChatEvent cancelled = chatStreamService.appendAndPublish(cancelEvent);
            latest = chatRunService.observeEvent(cancelled);
            chatRunLeaseService.markTerminal(run.id(), ChatRunExecutionStatus.CANCELLED);
            releaseContinuationInteractionClaim(run);
        } else {
            ChatEvent cancelEvent = RunCancelledEvent.of(run.id(), run.sessionId(), run.cancelReason(),
                    messageTarget.messageReady(), messageTarget.assistantMessageId());
            ChatRunTerminalCommitService.ExternalTerminalCommitResult result =
                    terminalCommitService.commitExternalTerminal(
                            ChatRunTerminalCommitService.ExternalTerminalCommitCommand.stop(
                                    cancelEvent, run, messageTarget.partialAssistant()));
            latest = result.run();
            chatRunService.synchronizeCommittedRunCache(latest);
            if (result.committed()) {
                publishTerminalBestEffort(result.event());
            }
        }
        return chatRunService.toStopResult(latest == null ? run : latest);
    }

    private void reconcileTerminalInteraction(ChatRun run) {
        if (terminalCommitService != null) {
            terminalCommitService.reconcileTerminalInteraction(run);
            return;
        }
        if (run != null && (run.status() == ChatRunStatus.CANCELLED || run.status() == ChatRunStatus.FAILED)) {
            releaseContinuationInteractionClaim(run);
        }
    }

    private void publishTerminalBestEffort(ChatEvent event) {
        try {
            chatStreamService.publishPersisted(event);
        } catch (RuntimeException ex) {
            log.warn("Chat run terminal event committed but realtime publish failed. runId={}, type={}, reason={}",
                    event == null ? null : event.runId(), event == null ? null : event.type(), ex.getMessage(), ex);
        }
    }

    private void releaseContinuationInteractionClaim(ChatRun run) {
        if (chatInteractionService == null || run == null || run.metadata() == null) {
            return;
        }
        Object value = run.metadata().get("interactionId");
        String interactionId = value == null ? null : String.valueOf(value).trim();
        if (interactionId == null || interactionId.isBlank()) {
            return;
        }
        chatInteractionService.markWaitingForRun(
                run.tenantId(), run.userId(), interactionId, run.id());
    }

    private void cancelDownstreamBestEffort(ChatRun run, UserContext user, RuntimeForwardHeaders headerSnapshot) {
        try {
            cancelDownstream(run, user, headerSnapshot)
                    .onErrorResume(ex -> {
                        log.warn("Downstream run cancel failed. runId={}, reason={}", run.id(), ex.getMessage());
                        return Mono.empty();
                    })
                    .subscribe();
        } catch (Exception ex) {
            log.warn("Downstream run cancel invocation failed. runId={}, reason={}", run.id(), ex.getMessage());
        }
    }

    public void stopActiveRunForSessionDelete(UserContext user, String sessionId) {
        Optional<ChatRun> active = chatRunService.findActiveRun(user, sessionId);
        active.ifPresent(run -> stopRunForSessionDelete(user, run, null));
    }

    public void stopRunForSessionDelete(UserContext user, ChatRun run, ChatSession sessionSnapshot) {
        if (run == null) {
            return;
        }
        stopRunNow(user, run.id(), "SESSION_DELETE", RuntimeForwardHeaders.empty(), sessionSnapshot);
    }

    private StopMessageTarget preparePartialAssistant(UserContext user, ChatRun run, String reason,
                                                       ChatSession sessionSnapshot) {
        if (run.assistantMessageId() != null && !run.assistantMessageId().isBlank()) {
            return StopMessageTarget.ready(run.assistantMessageId());
        }
        boolean interactionContinuation = interactionContinuation(run);
        boolean newTurnInteraction = InteractionMessageStrategy.newTurn(run);
        String interactionAssistantMessageId = interactionAssistantMessageId(run);
        if (interactionContinuation && !newTurnInteraction && interactionAssistantMessageId == null) {
            log.warn("Skip partial assistant persistence because Interaction continuation has no original assistant ID. runId={}",
                    run.id());
            return StopMessageTarget.notReady();
        }
        String parentMessageId = firstNonBlank(run.userMessageId(), run.parentMessageId());
        if (parentMessageId == null) {
            log.warn("Skip partial assistant persistence because run has no parent user message. runId={}", run.id());
            return StopMessageTarget.notReady();
        }
        try {
            AssistantAssembly assistant = new AssistantAssembly();
            chatStreamService.findPersistedRunEvents(user, run).forEach(assistant::observe);
            if (!assistant.shouldPersistMessage()) {
                return StopMessageTarget.notReady();
            }
            ChatSession session = sessionSnapshot == null ? sessionService.getSession(user, run.sessionId()) : sessionSnapshot;
            String assistantMessageId = interactionContinuation && !newTurnInteraction
                    ? interactionAssistantMessageId
                    : idGenerator.newId("msg",
                            IdGenerateContext.of(user.tenantId(), user.ownerUserId(), session.id(), run.id()));
            AssistantMessageSaveCommand partialAssistant = new AssistantMessageSaveCommand(
                    user.tenantId(),
                    user.ownerUserId(),
                    session,
                    assistant.finalContent(),
                    run.id(),
                    parentMessageId,
                    null,
                    assistant.parts(),
                    partialMetadata(reason),
                    assistantMessageId
            );
            return StopMessageTarget.ready(assistantMessageId, partialAssistant);
        } catch (Exception ex) {
            log.warn("Failed to prepare partial assistant on run stop. runId={}, reason={}, error={}",
                    run.id(), reason, ex.getMessage(), ex);
            return StopMessageTarget.notReady();
        }
    }

    private StopMessageTarget persistPreparedPartialAssistant(ChatRun run, StopMessageTarget target) {
        if (target == null || target.partialAssistant() == null) {
            return target == null ? StopMessageTarget.notReady() : target;
        }
        try {
            ChatMessage savedAssistant = persistPartialAssistant(run, target.partialAssistant());
            chatRunService.bindAssistantMessage(run.id(), savedAssistant.id());
            return StopMessageTarget.ready(savedAssistant.id());
        } catch (Exception ex) {
            log.warn("Failed to persist partial assistant on legacy run stop. runId={}, error={}",
                    run.id(), ex.getMessage(), ex);
            return StopMessageTarget.notReady();
        }
    }

    private ChatMessage persistPartialAssistant(ChatRun run, AssistantMessageSaveCommand command) {
        if (!interactionContinuation(run) || InteractionMessageStrategy.newTurn(run)) {
            return sessionService.saveAssistantMessage(command);
        }
        String assistantMessageId = interactionAssistantMessageId(run);
        if (assistantMessageId == null || !assistantMessageId.equals(command.normalizedMessageId())) {
            throw new IllegalStateException("Interaction stop partial assistant 必须复用原 assistantMessageId");
        }
        return sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                command.tenantId(),
                command.userId(),
                command.session(),
                assistantMessageId,
                command.content(),
                command.runId(),
                command.safePartDrafts(),
                command.metadataJson()
        ));
    }

    private boolean interactionContinuation(ChatRun run) {
        return metadataText(run, INTERACTION_ID_METADATA) != null;
    }

    private String interactionAssistantMessageId(ChatRun run) {
        return metadataText(run, INTERACTION_ASSISTANT_MESSAGE_ID_METADATA);
    }

    private String metadataText(ChatRun run, String key) {
        if (run == null || run.metadata() == null || key == null) {
            return null;
        }
        Object value = run.metadata().get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Mono<Void> cancelDownstream(ChatRun run, UserContext user, RuntimeForwardHeaders forwardHeaders) {
        if (run == null || run.runtimeProvider() == null || run.runtimeProvider().isBlank()) {
            return Mono.empty();
        }
        return agentRuntimeExecutor.cancel(run, user, forwardHeaders);
    }

    private RuntimeForwardHeaders normalizeForwardHeaders(RuntimeForwardHeaders forwardHeaders) {
        return forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "USER_STOP" : reason;
    }

    private String partialMetadata(String reason) {
        return "SESSION_DELETE".equals(reason)
                ? SESSION_DELETE_PARTIAL_ASSISTANT_METADATA
                : USER_STOP_PARTIAL_ASSISTANT_METADATA;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record StopMessageTarget(boolean messageReady, String assistantMessageId,
                                     AssistantMessageSaveCommand partialAssistant) {
        private static StopMessageTarget notReady() {
            return new StopMessageTarget(false, null, null);
        }

        private static StopMessageTarget ready(String assistantMessageId) {
            if (assistantMessageId == null || assistantMessageId.isBlank()) {
                return notReady();
            }
            return new StopMessageTarget(true, assistantMessageId, null);
        }

        private static StopMessageTarget ready(String assistantMessageId,
                                               AssistantMessageSaveCommand partialAssistant) {
            if (assistantMessageId == null || assistantMessageId.isBlank() || partialAssistant == null) {
                return notReady();
            }
            return new StopMessageTarget(true, assistantMessageId, partialAssistant);
        }
    }
}
