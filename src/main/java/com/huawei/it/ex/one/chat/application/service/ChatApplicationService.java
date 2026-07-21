package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatRunStartResult;
import com.huawei.it.ex.one.chat.domain.ChatRunStopResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stable application entry for chat run execution.
 *
 * <p>The existing frontend protocol remains implemented by the interface layer. This contract only separates that
 * interface layer from the workflow implementation while the DDD refactor is performed incrementally.</p>
 */
public interface ChatApplicationService {

    Flux<ChatEvent> executeRun(UserContext user, ChatCommand command, RuntimeForwardHeaders forwardHeaders);

    default Flux<ChatEvent> executeRun(UserContext user, TraceContext traceContext, ChatCommand command,
                                       RuntimeForwardHeaders forwardHeaders) {
        return executeRun(user, command, forwardHeaders);
    }

    default Flux<ChatEvent> executeRun(UserContext user, ChatCommand command) {
        return executeRun(user, command, RuntimeForwardHeaders.empty());
    }

    Mono<ChatRunStartResult> startRun(UserContext user, ChatCommand command, RuntimeForwardHeaders forwardHeaders);

    default Mono<ChatRunStartResult> startRun(UserContext user, TraceContext traceContext, ChatCommand command,
                                              RuntimeForwardHeaders forwardHeaders) {
        return startRun(user, command, forwardHeaders);
    }

    default Mono<ChatRunStartResult> startRun(UserContext user, ChatCommand command) {
        return startRun(user, command, RuntimeForwardHeaders.empty());
    }

    Mono<ChatRunStopResult> stopRun(UserContext user, String runId, RuntimeForwardHeaders forwardHeaders);

    default Mono<ChatRunStopResult> stopRun(UserContext user, TraceContext traceContext, String runId,
                                            RuntimeForwardHeaders forwardHeaders) {
        return stopRun(user, runId, forwardHeaders);
    }

    default Mono<ChatRunStopResult> stopRun(UserContext user, String runId) {
        return stopRun(user, runId, RuntimeForwardHeaders.empty());
    }
}
