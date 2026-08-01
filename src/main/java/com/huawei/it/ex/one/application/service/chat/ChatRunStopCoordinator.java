package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.common.trace.TraceContext;
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

import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Run 取消协调器。
 *
 * <p>stop 接口和删除会话都会经过这里收敛运行态：写 cancel flag、尽力取消下游 Runtime、
 * 取消本机订阅、固化用户可见 partial assistant，并发布标准 {@code run.cancelled}。
 * 下游取消永远是 best-effort，不能拖住 Servlet 删除或 stop 请求。</p>
 */
@Service
public class ChatRunStopCoordinator {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunStopCoordinator.class);
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
    private ChatWaitingStopCommitService waitingStopCommitService;

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

    /** 测试兼容构造器无需感知等待态 stop；生产 Spring 上下文会注入该提交器。 */
    @Autowired(required = false)
    void setWaitingStopCommitService(ChatWaitingStopCommitService waitingStopCommitService) {
        this.waitingStopCommitService = waitingStopCommitService;
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
        return stopRun(user, TraceContext.empty(), runId, reason, forwardHeaders);
    }

    public Mono<ChatRunStopResult> stopRun(UserContext user, TraceContext traceContext, String runId, String reason,
                                           RuntimeForwardHeaders forwardHeaders) {
        return Mono.defer(() -> Mono.just(stopRunNow(user, traceContext, runId, reason, forwardHeaders)));
    }

    public ChatRunStopResult stopRunNow(UserContext user, String runId, String reason,
                                        RuntimeForwardHeaders forwardHeaders) {
        return stopRunNow(user, TraceContext.empty(), runId, reason, forwardHeaders);
    }

    public ChatRunStopResult stopRunNow(UserContext user, TraceContext traceContext, String runId, String reason,
                                        RuntimeForwardHeaders forwardHeaders) {
        return stopRunNow(user, runId, reason,
                new StopRunContext(traceContext, forwardHeaders, null));
    }

    private ChatRunStopResult stopRunNow(UserContext user, String runId, String reason,
                                         StopRunContext stopContext) {
        ChatRun requestedRun = chatRunService.requireOwnedRun(user, runId);
        if (requestedRun.status() == ChatRunStatus.WAITING_USER && waitingStopCommitService != null) {
            return stopWaitingRun(user, requestedRun, reason, stopContext);
        }
        return stopActiveRun(user, requestedRun, reason, stopContext);
    }

    private ChatRunStopResult stopActiveRun(UserContext user, ChatRun requestedRun, String reason,
                                            StopRunContext stopContext) {
        String effectiveReason = normalizeReason(reason);
        RuntimeForwardHeaders headerSnapshot = stopContext.forwardHeaders();
        ChatRunStopDecision decision = chatRunService.requestStop(user, requestedRun, effectiveReason);
        ChatRun run = decision.run();
        if (!decision.appendCancelledEvent()) {
            reconcileTerminalInteraction(run);
            return chatRunService.toStopResult(run);
        }
        /*
         * 先通知下游，再 dispose 本机订阅。Relay WebSocket stop 需要命中仍存活的
         * outbound exchange；取消正确性已由 requestStop 写入的 cancel flag 和 guarded insert 保证。
         */
        cancelDownstreamBestEffort(run, user, stopContext.traceContext(), headerSnapshot);
        runExecutionRegistry.cancel(run.id());
        if (!chatRunService.shouldAcceptEvent(RunCancelledEvent.of(run.id(), run.sessionId(), run.cancelReason()))) {
            ChatRun latest = chatRunService.requireOwnedRun(user, run.id());
            reconcileTerminalInteraction(latest);
            return chatRunService.toStopResult(latest);
        }
        StopMessageTarget messageTarget = preparePartialAssistant(
                user, run, effectiveReason, stopContext.sessionSnapshot());
        ChatRun latest;
        if (terminalCommitService == null) {
            messageTarget = persistPreparedPartialAssistant(run, messageTarget);
            ChatEvent cancelEvent = RunCancelledEvent.of(run.id(), run.sessionId(), run.cancelReason(),
                    messageTarget.messageReady(), messageTarget.assistantMessageId());
            ChatEvent cancelled = chatStreamService.appendAndPublish(cancelEvent);
            latest = chatRunService.observeEvent(cancelled);
            chatRunLeaseService.markTerminal(run.id(), ChatRunExecutionStatus.CANCELLED);
            cancelContinuationInteractionClaim(run);
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

    private ChatRunStopResult stopWaitingRun(UserContext user, ChatRun sourceRun, String reason,
                                             StopRunContext stopContext) {
        String effectiveReason = normalizeReason(reason);
        ChatWaitingStopCommitService.WaitingStopCommitResult waiting =
                waitingStopCommitService.cancelWaiting(user, sourceRun, effectiveReason);
        ChatRun effectiveRun = waiting.effectiveRun();
        if (effectiveRun != null) {
            if ((effectiveRun.runtimeProvider() == null || effectiveRun.runtimeProvider().isBlank())
                    && waiting.runtimeTarget() != null) {
                cancelWaitingRuntimeBestEffort(
                        waiting.runtimeTarget(), user, effectiveReason, stopContext);
            }
            stopActiveRun(user, effectiveRun, effectiveReason, stopContext);
        } else if (waiting.interactionCancelled() && waiting.runtimeTarget() != null) {
            cancelWaitingRuntimeBestEffort(waiting.runtimeTarget(), user, effectiveReason, stopContext);
        }

        ChatRunStopResult sourceResult = chatRunService.toStopResult(sourceRun);
        if (waiting.interaction() == null) {
            return sourceResult;
        }
        String interactionStatus = waiting.interactionCancelled()
                ? "CANCELLED"
                : waiting.interaction().status().name();
        return sourceResult.withWaitingInteraction(
                waiting.interaction().id(),
                interactionStatus,
                waiting.interactionCancelledAt() == null
                        ? waiting.interaction().cancelledAt()
                        : waiting.interactionCancelledAt(),
                effectiveRun == null ? null : effectiveRun.id());
    }

    private void cancelWaitingRuntimeBestEffort(
            ChatWaitingStopCommitService.WaitingRuntimeTarget target,
            UserContext user,
            String reason,
            StopRunContext stopContext) {
        AgentRuntimeCancelRequest request = new AgentRuntimeCancelRequest(
                user.tenantId(),
                user.ownerUserId(),
                target == null ? null : target.sessionId(),
                target == null ? null : target.runId(),
                target == null ? null : target.runtimeSessionId(),
                target == null ? null : target.provider(),
                target == null ? null : target.runtimeTargetId(),
                reason,
                target == null || target.routeType() == null
                        ? Map.of()
                        : Map.of("routeType", target.routeType()),
                stopContext.forwardHeaders(),
                stopContext.traceContext());
        try {
            agentRuntimeExecutor.cancel(request)
                    .onErrorResume(ex -> {
                        log.warn(SystemErrorLogEntry.builder(cancelErrorCode(request.provider()),
                                        "Waiting Runtime cancellation failed")
                                .runId(request.runId())
                                .sessionId(request.sessionId())
                                .operation("chat-run.stop.waiting-runtime-cancel")
                                .attribute("runtimeProvider", request.provider())
                                .build(), ex);
                        return Mono.empty();
                    })
                    .subscribe();
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(cancelErrorCode(request.provider()),
                            "Waiting Runtime cancellation invocation failed")
                    .runId(request.runId())
                    .sessionId(request.sessionId())
                    .operation("chat-run.stop.waiting-runtime-cancel")
                    .attribute("runtimeProvider", request.provider())
                    .build(), ex);
        }
    }

    private void reconcileTerminalInteraction(ChatRun run) {
        if (terminalCommitService != null) {
            terminalCommitService.reconcileTerminalInteraction(run);
            return;
        }
        if (run != null && run.status() == ChatRunStatus.CANCELLED) {
            cancelContinuationInteractionClaim(run);
        } else if (run != null && run.status() == ChatRunStatus.FAILED) {
            releaseContinuationInteractionClaim(run);
        }
    }

    private void publishTerminalBestEffort(ChatEvent event) {
        try {
            chatStreamService.publishPersisted(event);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.WEBSOCKET_SEND_FAILED,
                            "ChatRun terminal event was committed but realtime publication failed")
                    .runId(event == null ? null : event.runId())
                    .operation("chat-run.stop.terminal-publish")
                    .attribute("eventType", event == null ? null : event.type())
                    .build());
        }
    }

    private void cancelContinuationInteractionClaim(ChatRun run) {
        if (chatInteractionService == null || run == null || run.metadata() == null) {
            return;
        }
        Object value = run.metadata().get("interactionId");
        String interactionId = value == null ? null : String.valueOf(value).trim();
        if (interactionId == null || interactionId.isBlank()) {
            return;
        }
        chatInteractionService.cancelRespondingForRun(
                run.tenantId(), run.userId(), interactionId, run.id(), java.time.Instant.now());
    }

    private void releaseContinuationInteractionClaim(ChatRun run) {
        if (chatInteractionService == null || run == null || run.metadata() == null) {
            return;
        }
        Object value = run.metadata().get(INTERACTION_ID_METADATA);
        String interactionId = value == null ? null : String.valueOf(value).trim();
        if (interactionId == null || interactionId.isBlank()) {
            return;
        }
        chatInteractionService.markWaitingForRun(
                run.tenantId(), run.userId(), interactionId, run.id());
    }

    private void cancelDownstreamBestEffort(ChatRun run, UserContext user, TraceContext traceContext,
                                            RuntimeForwardHeaders headerSnapshot) {
        try {
            cancelDownstream(run, user, traceContext, headerSnapshot)
                    .onErrorResume(ex -> {
                        log.warn(SystemErrorLogEntry.builder(cancelErrorCode(run),
                                        "Downstream run cancellation failed")
                                .runId(run.id())
                                .sessionId(run.sessionId())
                                .operation("chat-run.stop.downstream-cancel")
                                .attribute("runtimeProvider", run.runtimeProvider())
                                .build(), ex);
                        return Mono.empty();
                    })
                    .subscribe();
        } catch (Exception ex) {
            log.warn(SystemErrorLogEntry.builder(cancelErrorCode(run),
                            "Downstream run cancellation invocation failed")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.stop.downstream-cancel")
                    .attribute("runtimeProvider", run.runtimeProvider())
                    .build(), ex);
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
        stopRunNow(user, run.id(), "SESSION_DELETE",
                new StopRunContext(TraceContext.empty(), RuntimeForwardHeaders.empty(), sessionSnapshot));
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
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "Interaction continuation has no assistant ID; partial assistant persistence was skipped")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.stop.partial-assistant.prepare")
                    .retryable(false)
                    .build());
            return StopMessageTarget.notReady();
        }
        String parentMessageId = firstNonBlank(run.userMessageId(), run.parentMessageId());
        if (parentMessageId == null) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "ChatRun has no parent user message; partial assistant persistence was skipped")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.stop.partial-assistant.prepare")
                    .retryable(false)
                    .build());
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
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "Partial assistant preparation failed during ChatRun stop")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.stop.partial-assistant.prepare")
                    .attribute("stopReason", reason)
                    .build(), ex);
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
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_TRANSACTION_FAILED,
                            "Legacy partial assistant persistence failed during ChatRun stop")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.stop.partial-assistant.persist")
                    .build(), ex);
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

    private Mono<Void> cancelDownstream(ChatRun run, UserContext user, TraceContext traceContext,
                                        RuntimeForwardHeaders forwardHeaders) {
        if (run == null || run.runtimeProvider() == null || run.runtimeProvider().isBlank()) {
            return Mono.empty();
        }
        return agentRuntimeExecutor.cancel(run, user, traceContext, forwardHeaders);
    }

    private SystemErrorCode cancelErrorCode(ChatRun run) {
        return cancelErrorCode(run == null ? null : run.runtimeProvider());
    }

    private SystemErrorCode cancelErrorCode(String provider) {
        if ("relay".equalsIgnoreCase(provider)) {
            return SystemErrorCode.RELAY_INTERRUPT_FAILED;
        }
        if ("domain-agent".equalsIgnoreCase(provider)) {
            return SystemErrorCode.DOMAIN_AGENT_CANCEL_FAILED;
        }
        return SystemErrorCode.INTERNAL_EXECUTION_FAILED;
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

    private record StopRunContext(TraceContext traceContext, RuntimeForwardHeaders forwardHeaders,
                                  ChatSession sessionSnapshot) {
        private StopRunContext {
            traceContext = traceContext == null ? TraceContext.empty() : traceContext;
            forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
        }
    }
}
