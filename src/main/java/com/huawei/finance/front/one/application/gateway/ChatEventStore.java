package com.huawei.finance.front.one.application.gateway;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.List;

public interface ChatEventStore {
    void append(ChatEvent event);
    List<ChatEvent> findBySessionIdAndAfterSeq(String sessionId, long afterSeq);
    List<ChatEvent> findByRunId(String runId);
}
