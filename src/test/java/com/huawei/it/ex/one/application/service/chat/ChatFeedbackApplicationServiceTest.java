package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.integration.conversation.ChatEventStore;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunCache;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.memory.ChatFeedbackRepository;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessageFeedback;
import com.huawei.it.ex.one.domain.chat.ChatMessagePage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunCancelSignal;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatSessionPage;
import java.time.Instant;
import java.util.Collection;
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

        ChatMessageFeedback feedback = service.submit(user(), command("msg1", null, "like", "GOOD", "ok", null));

        assertThat(feedback.id()).isEqualTo("feedback_1");
        assertThat(feedback.rating()).isEqualTo("LIKE");
        assertThat(feedback.status()).isEqualTo("ACTIVE");
        assertThat(feedback.runId()).isNull();
        assertThat(feedback.reasonCode()).isEqualTo("GOOD");
        assertThat(feedback.commentText()).isEqualTo("ok");
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
                new RecordingFeedbackRepository(),
                new FixedIdGenerator(),
                null
        );

        assertThatThrownBy(() -> service.submit(user(), command("msg1", "run1", "LIKE", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assistant");
    }

    @Test
    void submitFeedbackRejectsMissingAssistantMessageIdAsBadRequest() {
        ChatFeedbackApplicationService service = new ChatFeedbackApplicationService(
                new PermissionChecker(),
                new InMemoryMessageRepository(null),
                new RecordingFeedbackRepository(),
                new FixedIdGenerator(),
                null
        );

        assertThatThrownBy(() -> service.submit(user(), command("undefined", null, "LIKE", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assistant messageId");
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
                new RecordingFeedbackRepository(),
                new FixedIdGenerator(),
                chatRunService(runs)
        );

        assertThatThrownBy(() -> service.submit(user(), command("msg1", "run2", "LIKE", null, null, null)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("同一会话");
    }

    @Test
    void cancelFeedbackMarksExistingFeedbackAsCancelled() {
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
        service.submit(user(), command("msg1", null, "LIKE", null, null, null));

        ChatMessageFeedback cancelled = service.cancel(user(), "msg1", null);

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(feedbacks.saved.status()).isEqualTo("CANCELLED");
        assertThat(service.findActiveByMessages(user(), "session1", List.of(messages.message))).isEmpty();
    }

    @Test
    void cancelFeedbackIsIdempotentWhenNoFeedbackExists() {
        InMemoryMessageRepository messages = new InMemoryMessageRepository(new ChatMessage(
                "msg1", "tenant1", "user1", "session1", "assistant", "answer", null, Instant.now()
        ));
        ChatFeedbackApplicationService service = new ChatFeedbackApplicationService(
                new PermissionChecker(),
                messages,
                new RecordingFeedbackRepository(),
                new FixedIdGenerator(),
                null
        );

        ChatMessageFeedback cancelled = service.cancel(user(), "msg1", null);

        assertThat(cancelled.id()).isNull();
        assertThat(cancelled.rating()).isNull();
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
    }

    @Test
    void findActiveByMessagesOnlyReturnsAssistantActiveFeedback() {
        ChatMessage assistant = new ChatMessage(
                "msg1", "tenant1", "user1", "session1", "assistant", "answer", null, Instant.now()
        );
        ChatMessage userMessage = new ChatMessage(
                "msg2", "tenant1", "user1", "session1", "user", "question", null, Instant.now()
        );
        InMemoryMessageRepository messages = new InMemoryMessageRepository(assistant);
        RecordingFeedbackRepository feedbacks = new RecordingFeedbackRepository();
        ChatFeedbackApplicationService service = new ChatFeedbackApplicationService(
                new PermissionChecker(),
                messages,
                feedbacks,
                new FixedIdGenerator(),
                null
        );
        ChatMessageFeedback feedback = service.submit(user(), command("msg1", null, "DISLIKE", null, null, null));

        Map<String, ChatMessageFeedback> active = service.findActiveByMessages(
                user(), "session1", List.of(assistant, userMessage));

        assertThat(active).containsEntry("msg1", feedback);
        assertThat(active).doesNotContainKey("msg2");
    }

    private ChatRunApplicationService chatRunService(InMemoryRunRepository runs) {
        return new ChatRunApplicationService(
                runs,
                new NoopRunCache(),
                new EmptyEventStore(),
                new PermissionChecker(),
                new FixedSessionRepository()
        );
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private MessageFeedbackCommand command(String messageId, String runId, String rating,
                                           String reasonCode, String commentText, Map<String, Object> metadata) {
        return new MessageFeedbackCommand(messageId, runId, rating, reasonCode, commentText, metadata);
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

        @Override
        public Optional<ChatMessageFeedback> cancel(String tenantId, String userId, String messageId, Instant cancelledAt) {
            if (saved == null || !tenantId.equals(saved.tenantId()) || !userId.equals(saved.userId())
                    || !messageId.equals(saved.messageId())) {
                return Optional.empty();
            }
            saved = new ChatMessageFeedback(
                    saved.id(),
                    saved.tenantId(),
                    saved.userId(),
                    saved.sessionId(),
                    saved.messageId(),
                    saved.runId(),
                    saved.rating(),
                    "CANCELLED",
                    saved.reasonCode(),
                    saved.commentText(),
                    saved.metadata(),
                    saved.createdAt(),
                    cancelledAt
            );
            return Optional.of(saved);
        }

        @Override
        public Map<String, ChatMessageFeedback> findActiveByMessages(
                String tenantId, String userId, String sessionId, Collection<String> messageIds) {
            if (saved == null || !"ACTIVE".equals(saved.status()) || !tenantId.equals(saved.tenantId())
                    || !userId.equals(saved.userId()) || !sessionId.equals(saved.sessionId())
                    || !messageIds.contains(saved.messageId())) {
                return Map.of();
            }
            return Map.of(saved.messageId(), saved);
        }

        @Override
        public Optional<ChatMessageFeedback> findByMessage(String tenantId, String userId, String messageId) {
            if (saved == null || !tenantId.equals(saved.tenantId()) || !userId.equals(saved.userId())
                    || !messageId.equals(saved.messageId())) {
                return Optional.empty();
            }
            return Optional.of(saved);
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
        @Override public ChatEvent appendWithExecutionGuard(ChatEvent event, com.huawei.it.ex.one.domain.chat.RunExecutionClaim claim) { return append(event); }
        @Override public List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId, String sessionId, long afterSeq) { return List.of(); }
        @Override public List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId, String runId, long afterSeq) { return List.of(); }
        @Override public long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId) { return 0L; }
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
