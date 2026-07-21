package com.huawei.it.ex.one.runtime.application.service;

import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.runtime.application.model.RuntimeExecutionContext;
import com.huawei.it.ex.one.runtime.application.model.RuntimeInteractionResponseContext;
import com.huawei.it.ex.one.runtime.application.model.RuntimeRunSnapshot;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.common.event.ChatEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Application contract for executing and cancelling a selected runtime. */
public interface RuntimeExecutionService {

    Flux<ChatEvent> execute(RuntimeExecutionContext context);

    Flux<ChatEvent> continueWithUserResponse(RuntimeInteractionResponseContext context);

    boolean supportsWaitingUserResponse(String runtimeProvider);

    Mono<Void> cancel(RuntimeRunSnapshot run, UserContext user, RuntimeForwardHeaders forwardHeaders);

    Mono<Void> cancel(RuntimeRunSnapshot run, UserContext user, TraceContext traceContext,
                      RuntimeForwardHeaders forwardHeaders);
}
