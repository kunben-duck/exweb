package com.huawei.finance.front.one.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.memory.ChatMessageRepository;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionApplicationServiceTest {
    @Test
    void listMessagesReturnsOwnedSessionHistoryInChronologicalOrder() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", "tenant1", "user1", "title", "ACTIVE", "web", now, now));
        messages.save(new ChatMessage("msg2", "tenant1", "user1", "session1", "assistant", "second", null, now.plusSeconds(2)));
        messages.save(new ChatMessage("msg1", "tenant1", "user1", "session1", "user", "first", null, now.plusSeconds(1)));

        SessionApplicationService service = service(sessions, messages);

        List<ChatMessage> history = service.listMessages(user(), "session1", null, 50).items();

        assertThat(history).extracting(ChatMessage::id).containsExactly("msg1", "msg2");
        assertThat(history).extracting(ChatMessage::content).containsExactly("first", "second");
    }

    private SessionApplicationService service(InMemorySessionRepository sessions, InMemoryMessageRepository messages) {
        return new SessionApplicationService(
                sessions,
                messages,
                new FixedIdGenerator(),
                new PermissionChecker()
        );
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private static class InMemorySessionRepository implements SessionRepository {
        private final Map<String, ChatSession> sessions = new HashMap<>();

        @Override
        public Optional<ChatSession> findById(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            return findById(sessionId)
                    .filter(session -> tenantId.equals(session.tenantId()))
                    .filter(session -> userId.equals(session.userId()));
        }

        @Override
        public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) {
            return sessions.values().stream()
                    .filter(session -> tenantId.equals(session.tenantId()))
                    .filter(session -> userId.equals(session.userId()))
                    .toList();
        }

        @Override
        public ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit) {
            return new ChatSessionPage(findByTenantIdAndUserId(tenantId, userId), null);
        }

        @Override
        public ChatSession save(ChatSession session) {
            sessions.put(session.id(), session);
            return session;
        }
    }

    private static class InMemoryMessageRepository implements ChatMessageRepository {
        private final List<ChatMessage> messages = new ArrayList<>();

        @Override
        public ChatMessage save(ChatMessage message) {
            messages.add(message);
            return message;
        }

        @Override
        public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
            return messages.stream()
                    .filter(message -> tenantId.equals(message.tenantId()))
                    .filter(message -> userId.equals(message.userId()))
                    .filter(message -> sessionId.equals(message.sessionId()))
                    .sorted(Comparator.comparing(ChatMessage::createdAt).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String cursor, int limit) {
            return new ChatMessagePage(messages.stream()
                    .filter(message -> tenantId.equals(message.tenantId()))
                    .filter(message -> userId.equals(message.userId()))
                    .filter(message -> sessionId.equals(message.sessionId()))
                    .sorted(Comparator.comparing(ChatMessage::createdAt).thenComparing(ChatMessage::id))
                    .limit(limit)
                    .toList(), null);
        }

        @Override
        public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
            return messages.stream()
                    .filter(message -> tenantId.equals(message.tenantId()))
                    .filter(message -> userId.equals(message.userId()))
                    .filter(message -> messageId.equals(message.id()))
                    .findFirst();
        }
    }

    private static class FixedIdGenerator implements IdGenerator {
        @Override
        public String newId(String prefix, IdGenerateContext context) {
            return prefix + "_1";
        }
    }
}
