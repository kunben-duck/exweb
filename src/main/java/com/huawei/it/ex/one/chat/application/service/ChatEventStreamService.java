package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.security.domain.UserContext;
import reactor.core.publisher.Flux;

/** Read-only event recovery boundary used by HTTP and WebSocket interfaces. */
public interface ChatEventStreamService {
    Flux<ChatEvent> resumeSession(UserContext user, String sessionId, long afterSeq);

    Flux<ChatEvent> resumeRun(UserContext user, String runId, long afterSeq);

    Flux<ChatEvent> resumeRunTopic(UserContext user, String topicId, long afterSeq);

    ChatRun ensureRunTopicAccessible(UserContext user, String topicId);

    long latestSeq(UserContext user, String sessionId);
}
