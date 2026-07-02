package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.finance.front.one.application.service.runtime.DomainAgentExecutor;
import com.huawei.finance.front.one.application.service.runtime.SubAgentExecutor;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.finance.front.one.domain.chat.ChatRunStopDecision;
import com.huawei.finance.front.one.domain.chat.ChatRunStopResult;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.RunCancelledEvent;
import com.huawei.finance.front.one.domain.routing.RouteType;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final SubAgentExecutor subAgentExecutor;
    private final DomainAgentExecutor domainAgentExecutor;
    private final IdGenerator idGenerator;

    public ChatRunStopCoordinator(SessionApplicationService sessionService,
                                  ChatStreamApplicationService chatStreamService,
                                  ChatRunApplicationService chatRunService,
                                  ChatRunLeaseApplicationService chatRunLeaseService,
                                  LocalChatRunExecutionRegistry runExecutionRegistry,
                                  AgentRuntimeExecutor agentRuntimeExecutor,
                                  SubAgentExecutor subAgentExecutor,
                                  DomainAgentExecutor domainAgentExecutor,
                                  IdGenerator idGenerator) {
        this.sessionService = sessionService;
        this.chatStreamService = chatStreamService;
        this.chatRunService = chatRunService;
        this.chatRunLeaseService = chatRunLeaseService;
        this.runExecutionRegistry = runExecutionRegistry;
        this.agentRuntimeExecutor = agentRuntimeExecutor;
        this.subAgentExecutor = subAgentExecutor;
        this.domainAgentExecutor = domainAgentExecutor;
        this.idGenerator = idGenerator;
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
            return chatRunService.toStopResult(run);
        }
        /*
         * 先通知下游，再 dispose 本机订阅。Relay WebSocket 的 interrupt 需要命中仍存活的
         * outbound exchange；取消正确性已由 requestStop 写入的 cancel flag 和 guarded insert 保证。
         */
        cancelDownstreamBestEffort(run, user, headerSnapshot);
        runExecutionRegistry.cancel(run.id());
        if (!chatRunService.shouldAcceptEvent(RunCancelledEvent.of(run.id(), run.sessionId(), run.cancelReason()))) {
            return chatRunService.toStopResult(run);
        }
        StopMessageTarget messageTarget = persistPartialAssistant(user, run, effectiveReason, sessionSnapshot);
        ChatEvent cancelEvent = RunCancelledEvent.of(run.id(), run.sessionId(), run.cancelReason(),
                messageTarget.messageReady(), messageTarget.assistantMessageId());
        ChatEvent cancelled = chatStreamService.appendAndPublish(cancelEvent);
        ChatRun latest = chatRunService.observeEvent(cancelled);
        chatRunLeaseService.markTerminal(run.id(), ChatRunExecutionStatus.CANCELLED);
        return chatRunService.toStopResult(latest == null ? run : latest);
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

    private StopMessageTarget persistPartialAssistant(UserContext user, ChatRun run, String reason,
                                                      ChatSession sessionSnapshot) {
        if (run.assistantMessageId() != null && !run.assistantMessageId().isBlank()) {
            return StopMessageTarget.ready(run.assistantMessageId());
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
            String assistantMessageId = idGenerator.newId("msg",
                    IdGenerateContext.of(user.tenantId(), user.userId(), session.id(), run.id()));
            ChatMessage savedAssistant = sessionService.saveAssistantMessage(new AssistantMessageSaveCommand(
                    user.tenantId(),
                    user.userId(),
                    session,
                    assistant.finalContent(),
                    run.id(),
                    parentMessageId,
                    null,
                    assistant.parts(),
                    partialMetadata(reason),
                    assistantMessageId
            ));
            chatRunService.bindAssistantMessage(run.id(), savedAssistant.id());
            return StopMessageTarget.ready(savedAssistant.id());
        } catch (Exception ex) {
            log.warn("Failed to persist partial assistant on run stop. runId={}, reason={}, error={}",
                    run.id(), reason, ex.getMessage(), ex);
            return StopMessageTarget.notReady();
        }
    }

    private Mono<Void> cancelDownstream(ChatRun run, UserContext user, RuntimeForwardHeaders forwardHeaders) {
        if (run == null || run.routeType() == null) {
            return Mono.empty();
        }
        if (RouteType.AGENT_RUNTIME.name().equals(run.routeType())) {
            return agentRuntimeExecutor.cancel(run, user, forwardHeaders);
        }
        if (RouteType.SUB_AGENT.name().equals(run.routeType())) {
            return subAgentExecutor.cancel(run, user);
        }
        if (RouteType.DOMAIN_AGENT.name().equals(run.routeType())) {
            return domainAgentExecutor.cancel(run, user, forwardHeaders);
        }
        return Mono.empty();
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

    private record StopMessageTarget(boolean messageReady, String assistantMessageId) {
        private static StopMessageTarget notReady() {
            return new StopMessageTarget(false, null);
        }

        private static StopMessageTarget ready(String assistantMessageId) {
            if (assistantMessageId == null || assistantMessageId.isBlank()) {
                return notReady();
            }
            return new StopMessageTarget(true, assistantMessageId);
        }
    }
}
