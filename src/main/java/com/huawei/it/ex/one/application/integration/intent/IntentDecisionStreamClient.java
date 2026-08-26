package com.huawei.it.ex.one.application.integration.intent;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

import reactor.core.publisher.Flux;

/**
 * IntentDecision streaming client boundary.
 *
 * <p>The client emits process frames followed by exactly one final recognition result. Transport
 * errors and downstream {@code error} events are retried inside the implementation according to the
 * configured {@link IntentRetryPolicy}.</p>
 */
public interface IntentDecisionStreamClient {
    /**
     * Recognize an intent through the configured streaming protocol.
     *
     * @param command current chat command.
     * @param memory current immutable memory context.
     * @param user current server-side user context.
     * @return ordered process and result frames.
     */
    Flux<IntentDecisionStreamFrame> recognize(ChatCommand command, MemoryContext memory, UserContext user);

    default Flux<IntentDecisionStreamFrame> recognize(ChatCommand command,
                                                      MemoryContext memory,
                                                      UserContext user,
                                                      String userMessageId) {
        return recognize(command, memory, user);
    }
}
