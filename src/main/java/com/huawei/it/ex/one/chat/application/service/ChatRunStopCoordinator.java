package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.mapper.ChatRuntimeMapper;

import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.runtime.application.service.RuntimeExecutionService;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunExecutionStatus;
import com.huawei.it.ex.one.chat.domain.ChatRunStatus;
import com.huawei.it.ex.one.chat.domain.ChatRunStopDecision;
import com.huawei.it.ex.one.chat.domain.ChatRunStopResult;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.RunCancelledEvent;
import java.util.Optional;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
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
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunStopCoordinator.class);
    private static final String INTERACTION_ID_METADATA = "interactionId";
    private final ChatStreamApplicationService chatStreamService;
    private final ChatRunApplicationService chatRunService;
    private final ChatRunLeaseApplicationService chatRunLeaseService;
    private final LocalChatRunExecutionRegistry runExecutionRegistry;
    private final RuntimeExecutionService agentRuntimeExecutor;
    private final ChatInteractionApplicationService chatInteractionService;
    private final ChatRunTerminalCommitService terminalCommitService;
    private final ChatRunStopMessageService stopMessageService;

    @Autowired
    public ChatRunStopCoordinator(ChatStreamApplicationService chatStreamService,
                                  ChatRunApplicationService chatRunService,
                                  ChatRunLeaseApplicationService chatRunLeaseService,
                                  LocalChatRunExecutionRegistry runExecutionRegistry,
                                  RuntimeExecutionService agentRuntimeExecutor,
                                  ChatInteractionApplicationService chatInteractionService,
                                  ChatRunTerminalCommitService terminalCommitService,
                                  ChatRunStopMessageService stopMessageService) {
        this.chatStreamService = chatStreamService;
        this.chatRunService = chatRunService;
        this.chatRunLeaseService = chatRunLeaseService;
        this.runExecutionRegistry = runExecutionRegistry;
        this.agentRuntimeExecutor = agentRuntimeExecutor;
        this.chatInteractionService = chatInteractionService;
        this.terminalCommitService = terminalCommitService;
        this.stopMessageService = stopMessageService;
    }

    public ChatRunStopCoordinator(SessionApplicationService sessionService,
                                  ChatStreamApplicationService chatStreamService,
                                  ChatRunApplicationService chatRunService,
                                  ChatRunLeaseApplicationService chatRunLeaseService,
                                  LocalChatRunExecutionRegistry runExecutionRegistry,
                                  RuntimeExecutionService agentRuntimeExecutor,
                                  ChatInteractionApplicationService chatInteractionService,
                                  ChatRunTerminalCommitService terminalCommitService,
                                  IdGenerator idGenerator) {
        this(chatStreamService, chatRunService, chatRunLeaseService, runExecutionRegistry,
                agentRuntimeExecutor, chatInteractionService, terminalCommitService,
                new ChatRunStopMessageService(sessionService, chatStreamService, chatRunService, idGenerator));
    }

    public ChatRunStopCoordinator(SessionApplicationService sessionService,
                                  ChatStreamApplicationService chatStreamService,
                                  ChatRunApplicationService chatRunService,
                                  ChatRunLeaseApplicationService chatRunLeaseService,
                                  LocalChatRunExecutionRegistry runExecutionRegistry,
                                  RuntimeExecutionService agentRuntimeExecutor,
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
                                  RuntimeExecutionService agentRuntimeExecutor,
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
        String effectiveReason = normalizeReason(reason);
        RuntimeForwardHeaders headerSnapshot = stopContext.forwardHeaders();
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
        cancelDownstreamBestEffort(run, user, stopContext.traceContext(), headerSnapshot);
        runExecutionRegistry.cancel(run.id());
        if (!chatRunService.shouldAcceptEvent(RunCancelledEvent.of(run.id(), run.sessionId(), run.cancelReason()))) {
            ChatRun latest = chatRunService.requireOwnedRun(user, run.id());
            reconcileTerminalInteraction(latest);
            return chatRunService.toStopResult(latest);
        }
        ChatRunStopMessageService.StopMessageTarget messageTarget = stopMessageService.preparePartialAssistant(
                user, run, effectiveReason, stopContext.sessionSnapshot());
        ChatRun latest;
        if (terminalCommitService == null) {
            messageTarget = stopMessageService.persistPreparedPartialAssistant(run, messageTarget);
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
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.WEBSOCKET_SEND_FAILED,
                            "ChatRun terminal event was committed but realtime publication failed")
                    .runId(event == null ? null : event.runId())
                    .operation("chat-run.stop.terminal-publish")
                    .attribute("eventType", event == null ? null : event.type())
                    .build());
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

    private Mono<Void> cancelDownstream(ChatRun run, UserContext user, TraceContext traceContext,
                                        RuntimeForwardHeaders forwardHeaders) {
        if (run == null || run.runtimeProvider() == null || run.runtimeProvider().isBlank()) {
            return Mono.empty();
        }
        return agentRuntimeExecutor.cancel(ChatRuntimeMapper.run(run), user, traceContext, forwardHeaders);
    }

    private SystemErrorCode cancelErrorCode(ChatRun run) {
        if (run != null && "relay".equalsIgnoreCase(run.runtimeProvider())) {
            return SystemErrorCode.RELAY_INTERRUPT_FAILED;
        }
        if (run != null && "domain-agent".equalsIgnoreCase(run.runtimeProvider())) {
            return SystemErrorCode.DOMAIN_AGENT_CANCEL_FAILED;
        }
        return SystemErrorCode.INTERNAL_EXECUTION_FAILED;
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "USER_STOP" : reason;
    }

    private record StopRunContext(TraceContext traceContext, RuntimeForwardHeaders forwardHeaders,
                                  ChatSession sessionSnapshot) {
        private StopRunContext {
            traceContext = traceContext == null ? TraceContext.empty() : traceContext;
            forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
        }
    }
}
