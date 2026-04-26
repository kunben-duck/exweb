package com.huawei.finance.front.one.infrastructure.persistence;

import com.huawei.finance.front.one.application.gateway.ChatEventStore;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryChatEventStore implements ChatEventStore {
    private final List<ChatEvent> store = new CopyOnWriteArrayList<>();
    @Override public void append(ChatEvent event) { store.add(event); }
    @Override public List<ChatEvent> findBySessionIdAndAfterSeq(String sessionId, long afterSeq) {
        return new ArrayList<>(store.stream().filter(e -> sessionId.equals(e.sessionId()) && e.sequence() > afterSeq).toList());
    }
    @Override public List<ChatEvent> findByRunId(String runId) { return new ArrayList<>(store.stream().filter(e -> runId.equals(e.runId())).toList()); }
}
