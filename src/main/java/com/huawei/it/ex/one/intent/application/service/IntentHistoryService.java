package com.huawei.it.ex.one.intent.application.service;

import com.huawei.it.ex.one.intent.application.model.IntentMessageSnapshot;
import java.util.List;

/** Supplies recent chat history to the optional intent memory feature. */
public interface IntentHistoryService {
    List<IntentMessageSnapshot> findRecentMessages(String tenantId, String userId, String sessionId, int limit);
}
