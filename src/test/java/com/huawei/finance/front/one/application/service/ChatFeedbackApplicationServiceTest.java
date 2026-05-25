package com.huawei.finance.front.one.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorCache;
import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorRepository;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunCache;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.integration.memory.ChatFeedbackRepository;
import com.huawei.finance.front.one.application.integration.memory.ChatMessageRepository;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessageFeedback;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatReadCursor;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunCancelSignal;
import com.huawei.finance.front.one.domain.chat.ChatRunStatus;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChatFeedbackApplicationServiceTest {
    @Test
    void submitFeedbackRequiresOwnedAssistantMessage() {
        InMemoryMessageRepository messages = new InMemoryMessageRepository(new ChatMessage(
                "msg1", "tenant1", "user1", "session1", "assistant", "answer", null, Instant.now()
        ));
        RecordingFeedbackRepository feedbacks = new RecordingFeedbackRepository();
        ChatFeedbackApplicationService service = new ChatFeedbackApplicationService(
                new PermissionChecker(),
                messages,
                feedbacks,
                new FixedIdGenerator(),
                null
        );

        ChatMessageFeedback feedback = service.submit(user(), "msg1", null, "like", "GOOD", "ok", null);

        assertThat(feedback.id()).isEqualTo("feedback_1");
        assertThat(feedback.rating()).isEqualTo("LIKE");
        assertThat(feedback.runId()).isNull();
        assertThat(feedbacks.saved).isSameAs(feedback);
    }

    @Test
    void submitFeedbackRejectsUserMessage() {
        InMemoryMessageRepository messages = new InMemoryMessageRepository(new ChatMessage(
                "msg1", "tenant1", "user1", "session1", "user", "question", null, Instant.now()
        ));
        ChatFeedbackApplicationService service = new ChatFeedbackApplicationService(
                new PermissionChecker(),
                messages,
                feedback -> feedback,
                new FixedIdGenerator(),
                null
        );

        assertThatThrownBy(() -> service.submit(user(), "msg1", "run1", "LIKE", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assistant");
    }

    @Test
    void submitFeedbackRejectsRunFromDifferentSession() {
        InMemoryMessageRepository messages = new InMemoryMessageRepository(new ChatMessage(
                "msg1", "tenant1", "user1", "session1", "assistant", "answer", null, Instant.now()
        ));
        InMemoryRunRepository runs = new InMemoryRunRepository();
        runs.save(run("run2", "session2"));
        ChatFeedbackApplicationService service = new ChatFeedbackApplicationService(
                new PermissionChecker(),
                messages,
                feedback -> feedback,
                new FixedIdGenerator(),
                chatRunService(runs)
        );

        assertThatThrownBy(() -> service.submit(user(), "msg1", "run2", "LIKE", null, null, null))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("同一会话");
    }

    private ChatRunApplicationService chatRunService(InMemoryRunRepository runs) {
        return new ChatRunApplicationService(
                runs,
                new NoopRunCache(),
                new EmptyEventStore(),
                readCursorService(),
                new PermissionChecker(),
                new FixedSessionRepository()
        );
    }

    private ChatReadCursorApplicationService readCursorService() {
        return new ChatReadCursorApplicationService(
                new EmptyReadCursorRepository(),
                new EmptyReadCursorCache(),
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatReadCursorProperties()
        );
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private ChatRun run(String id, String sessionId) {
        Instant now = Instant.now();
        return new ChatRun(id, "tenant1", "user1", sessionId, ChatRunStatus.RUNNING,
                "AGENT_RUNTIME", null, "relay", null, null, null, null,
                now, null, Map.of(), now, now);
    }

    private static class InMemoryMessageRepository implements ChatMessageRepository {
        private final ChatMessage message;

        private InMemoryMessageRepository(ChatMessage message) {
            this.message = message;
        }

        @Override
        public ChatMessage save(ChatMessage message) {
            return message;
        }

        @Override
        public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
            return List.of(message);
        }

        @Override
        public ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String cursor, int limit) {
            return new ChatMessagePage(List.of(message), null);
        }

        @Override
        public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
            return Optional.ofNullable(message)
                    .filter(item -> tenantId.equals(item.tenantId()))
                    .filter(item -> userId.equals(item.userId()))
                    .filter(item -> messageId.equals(item.id()));
        }
    }

    private static class RecordingFeedbackRepository implements ChatFeedbackRepository {
        private ChatMessageFeedback saved;

        @Override
        public ChatMessageFeedback save(ChatMessageFeedback feedback) {
            saved = feedback;
            return feedback;
        }
    }

    private static class InMemoryRunRepository implements ChatRunRepository {
        private final Map<String, ChatRun> runs = new HashMap<>();

        @Override
        public ChatRun save(ChatRun run) {
            runs.put(run.id(), run);
            return run;
        }

        @Override
        public Optional<ChatRun> findById(String runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
            return findById(runId)
                    .filter(run -> tenantId.equals(run.tenantId()))
                    .filter(run -> userId.equals(run.userId()));
        }

        @Override
        public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) {
            return Optional.empty();
        }
    }

    private static class NoopRunCache implements ChatRunCache {
        @Override public Optional<ChatRun> getActive(String tenantId, String userId, String sessionId) { return Optional.empty(); }
        @Override public boolean tryClaimActive(ChatRun run) { return true; }
        @Override public void putActive(ChatRun run) {}
        @Override public void evictActive(String tenantId, String userId, String sessionId) {}
        @Override public void markCancellationRequested(String runId) {}
        @Override public ChatRunCancelSignal cancellationSignal(String runId) { return ChatRunCancelSignal.NOT_REQUESTED; }
    }

    private static class EmptyEventStore implements ChatEventStore {
        @Override public ChatEvent append(ChatEvent event) { return event; }
        @Override public List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId, String sessionId, long afterSeq) { return List.of(); }
        @Override public List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId, String runId, long afterSeq) { return List.of(); }
        @Override public long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId) { return 0L; }
    }

    private static class EmptyReadCursorRepository implements ChatReadCursorRepository {
        @Override
        public Optional<ChatReadCursor> find(String tenantId, String userId, String sessionId) {
            return Optional.empty();
        }

        @Override
        public ChatReadCursor upsert(String tenantId, String userId, String sessionId, long lastConsumedSeq) {
            return new ChatReadCursor("cursor1", tenantId, userId, sessionId, lastConsumedSeq, Instant.now());
        }
    }

    private static class EmptyReadCursorCache implements ChatReadCursorCache {
        @Override
        public Optional<ChatReadCursor> find(String tenantId, String userId, String sessionId) {
            return Optional.empty();
        }

        @Override
        public void put(ChatReadCursor cursor) {
        }
    }

    private static class FixedSessionRepository implements SessionRepository {
        @Override public Optional<ChatSession> findById(String sessionId) { return Optional.empty(); }
        @Override public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            Instant now = Instant.now();
            return Optional.of(new ChatSession(sessionId, tenantId, userId, "title", "ACTIVE", "web", now, now));
        }
        @Override public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) { return List.of(); }
        @Override public ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit) {
            return new ChatSessionPage(List.of(), null);
        }
        @Override public ChatSession save(ChatSession session) { return session; }
    }

    private static class FixedIdGenerator implements IdGenerator {
        @Override
        public String newId(String prefix, IdGenerateContext context) {
            return prefix + "_1";
        }
    }
}
