package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.repository.ChatMessageRepository;
import com.huawei.it.ex.one.intent.application.service.IntentHistoryService;
import com.huawei.it.ex.one.intent.application.model.IntentMessageSnapshot;
import java.util.List;
import org.springframework.stereotype.Service;

/** Maps Chat-owned message facts to the snapshot accepted by Intent. */
@Service
public class ChatIntentHistorySource implements IntentHistoryService {
    private final ChatMessageRepository messages;

    public ChatIntentHistorySource(ChatMessageRepository messages) {
        this.messages = messages;
    }

    @Override
    public List<IntentMessageSnapshot> findRecentMessages(
            String tenantId, String userId, String sessionId, int limit) {
        return messages.findRecentMessages(tenantId, userId, sessionId, limit).stream()
                .map(message -> new IntentMessageSnapshot(
                        message.id(), message.role(), message.content(), message.createdAt()))
                .toList();
    }
}
