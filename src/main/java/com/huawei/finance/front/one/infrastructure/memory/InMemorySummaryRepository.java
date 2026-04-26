package com.huawei.finance.front.one.infrastructure.memory;

import com.huawei.finance.front.one.application.gateway.SummaryRepository;
import com.huawei.finance.front.one.domain.memory.ConversationSummary;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemorySummaryRepository implements SummaryRepository {
    private final Map<String, ConversationSummary> store = new ConcurrentHashMap<>();
    @Override public Optional<ConversationSummary> findLatestBySessionId(String sessionId) { return Optional.ofNullable(store.get(sessionId)); }
    @Override public void save(ConversationSummary summary) { store.put(summary.sessionId(), summary); }
}
