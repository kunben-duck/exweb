package com.huawei.finance.front.one.application.integration.memory;

import com.huawei.finance.front.one.domain.memory.LongTermMemoryItem;
import java.util.List;

public interface LongTermMemoryStore {
    List<LongTermMemoryItem> searchRelevant(String tenantId, String userId, String query, int topK);
    void save(LongTermMemoryItem item);
}
