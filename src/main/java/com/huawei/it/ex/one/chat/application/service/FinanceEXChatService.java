package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.coordinator.FinanceChatOrchestrator;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatRunStartResult;
import com.huawei.it.ex.one.chat.domain.ChatRunStopResult;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.security.domain.UserContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Stable Chat application entry that delegates workflow execution to the orchestrator. */
@Service
public class FinanceEXChatService implements ChatApplicationService {
    private final FinanceChatOrchestrator orchestrator;

    public FinanceEXChatService(FinanceChatOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public Mono<ChatRunStartResult> startRun(UserContext user, ChatCommand command,
                                             RuntimeForwardHeaders forwardHeaders) {
        return orchestrator.startRun(user, TraceContext.empty(), command, forwardHeaders);
    }

    @Override
    public Mono<ChatRunStartResult> startRun(UserContext user, TraceContext traceContext,
                                             ChatCommand command, RuntimeForwardHeaders forwardHeaders) {
        return orchestrator.startRun(user, traceContext, command, forwardHeaders);
    }

    @Override
    public Mono<ChatRunStopResult> stopRun(UserContext user, String runId,
                                           RuntimeForwardHeaders forwardHeaders) {
        return orchestrator.stopRun(user, TraceContext.empty(), runId, forwardHeaders);
    }

    @Override
    public Mono<ChatRunStopResult> stopRun(UserContext user, TraceContext traceContext, String runId,
                                           RuntimeForwardHeaders forwardHeaders) {
        return orchestrator.stopRun(user, traceContext, runId, forwardHeaders);
    }

    @Override
    public Flux<ChatEvent> executeRun(UserContext user, ChatCommand command,
                                      RuntimeForwardHeaders forwardHeaders) {
        return orchestrator.executeRun(user, TraceContext.empty(), command, forwardHeaders);
    }

    @Override
    public Flux<ChatEvent> executeRun(UserContext user, TraceContext traceContext, ChatCommand command,
                                      RuntimeForwardHeaders forwardHeaders) {
        return orchestrator.executeRun(user, traceContext, command, forwardHeaders);
    }
}
