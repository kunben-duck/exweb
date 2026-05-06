package com.huawei.finance.front.one.application.integration.memory;

import com.huawei.finance.front.one.domain.memory.ConversationSummary;
import java.util.Optional;

public interface SummaryRepository {
    Optional<ConversationSummary> findLatestBySessionId(String sessionId);
    void save(ConversationSummary summary);
}
