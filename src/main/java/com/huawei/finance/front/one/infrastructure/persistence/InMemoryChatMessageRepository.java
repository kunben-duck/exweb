package com.huawei.finance.front.one.infrastructure.persistence;

import com.huawei.finance.front.one.application.gateway.ChatMessageRepository;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("legacy-inmemory")
public class InMemoryChatMessageRepository implements ChatMessageRepository {
    private final List<ChatMessage> store = new CopyOnWriteArrayList<>();
    @Override public ChatMessage save(ChatMessage message) { store.add(message); return message; }
    @Override public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
        if (sessionId == null) return List.of();
        List<ChatMessage> list = new ArrayList<>(store.stream().filter(m -> matches(tenantId, userId, sessionId, m)).toList());
        list.sort(Comparator.comparing(ChatMessage::createdAt).reversed());
        return list.stream().limit(limit).toList();
    }

    private boolean matches(String tenantId, String userId, String sessionId, ChatMessage message) {
        if (!sessionId.equals(message.sessionId())) return false;
        if (tenantId != null && !tenantId.equals(message.tenantId())) return false;
        return userId == null || userId.equals(message.userId());
    }
}
