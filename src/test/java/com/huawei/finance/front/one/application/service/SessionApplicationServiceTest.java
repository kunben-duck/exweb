package com.huawei.finance.front.one.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.memory.ChatMessageRepository;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ActiveRunExistsException;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessageAttachment;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePart;
import com.huawei.finance.front.one.domain.chat.ChatMessagePartDraft;
import com.huawei.finance.front.one.domain.chat.ChatRunMessagePlan;
import com.huawei.finance.front.one.domain.chat.ChatRunMode;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void normalRunCreatesUserAndAssistantAsActivePath() {
        TestFixture fixture = fixture();
        ChatRunMessagePlan plan = fixture.service.prepareRunMessage(user(), command("hello", ChatRunMode.NEXT,
                null, null, null), fixture.session, "run1", List.of());

        ChatMessage assistant = fixture.service.saveAssistantMessage("tenant1", "user1", fixture.session,
                "world", "run1", plan.userMessage().id(), null);
        List<ChatMessage> activePath = fixture.service.listMessages(user(), fixture.session.id(), null, 50).items();

        assertThat(plan.userMessage().parentMessageId()).isNull();
        assertThat(assistant.parentMessageId()).isEqualTo(plan.userMessage().id());
        assertThat(activePath).extracting(ChatMessage::role).containsExactly("user", "assistant");
        assertThat(fixture.sessions.findById(fixture.session.id()).orElseThrow().currentLeafMessageId())
                .isEqualTo(assistant.id());
    }

    @Test
    void editingHistoricalUserMessageCreatesSiblingWithoutChangingOriginal() {
        TestFixture fixture = fixture();
        MessagePair original = completeTurn(fixture, "原始问题", "原始回答", "run1");

        ChatRunMessagePlan editedPlan = fixture.service.prepareRunMessage(user(),
                command("编辑后的问题", ChatRunMode.EDIT_USER, null, original.user().id(), null),
                fixture.session, "run2", List.of());

        assertThat(editedPlan.userMessage().id()).isNotEqualTo(original.user().id());
        assertThat(editedPlan.userMessage().editedFromMessageId()).isEqualTo(original.user().id());
        assertThat(editedPlan.userMessage().parentMessageId()).isEqualTo(original.user().parentMessageId());
        assertThat(editedPlan.userMessage().siblingIndex()).isEqualTo(2);
        assertThat(fixture.service.listVariants(user(), fixture.session.id(), original.user().id()))
                .extracting(ChatMessage::content)
                .containsExactly("原始问题", "编辑后的问题");
    }

    @Test
    void regeneratingAssistantCreatesAssistantSiblingUnderSameUser() {
        TestFixture fixture = fixture();
        MessagePair original = completeTurn(fixture, "问题", "第一次回答", "run1");

        ChatRunMessagePlan regeneratePlan = fixture.service.prepareRunMessage(user(),
                command(null, ChatRunMode.REGENERATE_ASSISTANT, null, null, original.assistant().id()),
                fixture.session, "run2", List.of());
        ChatMessage regenerated = fixture.service.saveAssistantMessage("tenant1", "user1", fixture.session,
                "第二次回答", "run2", regeneratePlan.userMessage().id(), regeneratePlan.regeneratedFromMessageId());

        assertThat(regeneratePlan.userMessage().id()).isEqualTo(original.user().id());
        assertThat(regenerated.parentMessageId()).isEqualTo(original.user().id());
        assertThat(regenerated.regeneratedFromMessageId()).isEqualTo(original.assistant().id());
        assertThat(regenerated.siblingIndex()).isEqualTo(2);
        assertThat(fixture.service.listVariants(user(), fixture.session.id(), original.assistant().id()))
                .extracting(ChatMessage::content)
                .containsExactly("第一次回答", "第二次回答");
    }

    @Test
    void branchCopiesReadonlySnapshotAndRejectsEditingSnapshotMessages() {
        TestFixture fixture = fixture();
        MessagePair original = completeTurn(fixture, "报销问题", "报销回答", "run1");

        ChatSession branch = fixture.service.createBranch(user(), fixture.session.id(), original.assistant().id(), "报销分支");
        List<ChatMessage> branchPath = fixture.service.listMessages(user(), branch.id(), null, 50).items();

        assertThat(branch.branchSourceSessionId()).isEqualTo(fixture.session.id());
        assertThat(branch.branchSourceMessageId()).isEqualTo(original.assistant().id());
        assertThat(branchPath).hasSize(2);
        assertThat(branchPath).allSatisfy(message -> {
            assertThat(message.locked()).isTrue();
            assertThat(message.originType()).isEqualTo("BRANCH_SNAPSHOT");
            assertThat(message.sourceSessionId()).isEqualTo(fixture.session.id());
        });
        assertThatThrownBy(() -> fixture.service.prepareRunMessage(user(),
                command("不能编辑快照", ChatRunMode.EDIT_USER, null, branchPath.getFirst().id(), null),
                branch, "run2", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("分支历史快照");
    }

    @Test
    void deleteSessionMarksDeletedAndCancelsRuntimeBinding() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        ChatSession session = sessions.save(new ChatSession("session1", "tenant1", "user1", "title", "ACTIVE", "web",
                Instant.now(), Instant.now()));
        CountingRuntimeBindingService bindings = new CountingRuntimeBindingService();
        SessionApplicationService service = service(sessions, messages, new GuardChatRunService(false), bindings);

        ChatSession deleted = service.deleteSession(user(), session.id());

        assertThat(deleted.status()).isEqualTo("DELETED");
        assertThat(bindings.cancellations).isEqualTo(1);
        assertThat(service.listSessions(user(), null, 20).items()).isEmpty();
        assertThatThrownBy(() -> service.getSession(user(), session.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    void deleteSessionRejectsActiveRunAndKeepsSessionVisible() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        ChatSession session = sessions.save(new ChatSession("session1", "tenant1", "user1", "title", "ACTIVE", "web",
                Instant.now(), Instant.now()));
        CountingRuntimeBindingService bindings = new CountingRuntimeBindingService();
        SessionApplicationService service = service(sessions, messages, new GuardChatRunService(true), bindings);

        assertThatThrownBy(() -> service.deleteSession(user(), session.id()))
                .isInstanceOf(ActiveRunExistsException.class);
        assertThat(sessions.findById(session.id()).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(bindings.cancellations).isZero();
    }

    @Test
    void findFirstAssistantAnswersReturnsOneAnswerPerSession() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        Instant now = Instant.now();
        ChatSession first = sessions.save(new ChatSession("session1", "tenant1", "user1", "first", "ACTIVE", "web", now, now));
        ChatSession second = sessions.save(new ChatSession("session2", "tenant1", "user1", "second", "ACTIVE", "web", now, now));
        messages.save(new ChatMessage("msg1", "tenant1", "user1", "session1", null, 1L, 0, 1,
                "user", "问题一", null, "run1", "NORMAL", false, null, null, null, null, null, now));
        messages.save(new ChatMessage("msg2", "tenant1", "user1", "session1", "msg1", 2L, 1, 1,
                "assistant", "第一条回答", null, "run1", "NORMAL", false, null, null, null, null, null, now.plusSeconds(1)));
        messages.save(new ChatMessage("msg3", "tenant1", "user1", "session1", "msg1", 3L, 1, 2,
                "assistant", "第二条回答", null, "run2", "NORMAL", false, null, null, null, null, null, now.plusSeconds(2)));
        messages.save(new ChatMessage("msg4", "tenant1", "user1", "session2", null, 1L, 0, 1,
                "user", "尚未回答", null, "run3", "NORMAL", false, null, null, null, null, null, now));
        SessionApplicationService service = service(sessions, messages);

        Map<String, String> firstAnswers = service.findFirstAssistantAnswers(user(), List.of(first, second));

        assertThat(firstAnswers).containsEntry("session1", "第一条回答");
        assertThat(firstAnswers).doesNotContainKey("session2");
    }

    @Test
    void assistantMessagePartsHaveStableDisplaySemantics() {
        TestFixture fixture = fixture();
        ChatRunMessagePlan plan = fixture.service.prepareRunMessage(user(), command("hello", ChatRunMode.NEXT,
                null, null, null), fixture.session, "run1", List.of());

        ChatMessage assistant = fixture.service.saveAssistantMessage("tenant1", "user1", fixture.session,
                "最终回答", "run1", plan.userMessage().id(), null,
                List.of(
                        new ChatMessagePartDraft("PROGRESS", "relay-progress", "处理中", Map.of("text", "处理中")),
                        new ChatMessagePartDraft("TOOL", "tool_call_streaming", "search: 查询流程",
                                Map.of("toolName", "search", "inputPreview", "查询流程")),
                        new ChatMessagePartDraft("THINKING", "thinking-operation-end", "ENDED: op1",
                                Map.of("status", "ENDED", "operationId", "op1"))
                ));

        assertThat(assistant.parts()).extracting(ChatMessagePart::partType)
                .containsExactly("PROGRESS", "TOOL", "THINKING", "ANSWER");
        assertThat(assistant.parts()).extracting(ChatMessagePart::channel)
                .containsExactly("progress", "tool", "thinking", "answer");
        assertThat(assistant.parts()).extracting(ChatMessagePart::status)
                .containsExactly("STREAMING", "STREAMING", "COMPLETED", "COMPLETED");
        assertThat(assistant.parts()).extracting(ChatMessagePart::displayHint)
                .containsExactly("inline", "collapsible", "collapsible", "hidden");
        assertThat(assistant.parts()).extracting(ChatMessagePart::visible)
                .containsExactly(true, true, true, false);
    }

    @Test
    void listMessageTreeReturnsAllVisibleNodesIncludingSiblingVersions() {
        TestFixture fixture = fixture();
        MessagePair original = completeTurn(fixture, "原始问题", "原始回答", "run1");
        fixture.service.prepareRunMessage(user(),
                command("编辑后的问题", ChatRunMode.EDIT_USER, null, original.user().id(), null),
                fixture.session, "run2", List.of());
        ChatRunMessagePlan regeneratePlan = fixture.service.prepareRunMessage(user(),
                command(null, ChatRunMode.REGENERATE_ASSISTANT, null, null, original.assistant().id()),
                fixture.session, "run3", List.of());
        fixture.service.saveAssistantMessage("tenant1", "user1", fixture.session,
                "重新生成回答", "run3", regeneratePlan.userMessage().id(), regeneratePlan.regeneratedFromMessageId());

        List<ChatMessage> tree = fixture.service.listMessageTree(user(), fixture.session.id());

        assertThat(tree).extracting(ChatMessage::content)
                .contains("原始问题", "原始回答", "编辑后的问题", "重新生成回答");
        assertThat(tree.stream().filter(message -> original.user().id().equals(message.parentMessageId())))
                .extracting(ChatMessage::content)
                .containsExactly("原始回答", "重新生成回答");
    }

    @Test
    void deleteSessionsDeletesAllAfterValidation() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", "tenant1", "user1", "first", "ACTIVE", "web", now, now));
        sessions.save(new ChatSession("session2", "tenant1", "user1", "second", "ARCHIVED", "web", now, now));
        CountingRuntimeBindingService bindings = new CountingRuntimeBindingService();
        SessionApplicationService service = service(sessions, messages, new GuardChatRunService(false), bindings);

        List<ChatSession> deleted = service.deleteSessions(user(), List.of("session1", "session2", "session1"));

        assertThat(deleted).extracting(ChatSession::id).containsExactly("session1", "session2");
        assertThat(deleted).extracting(ChatSession::status).containsExactly("DELETED", "DELETED");
        assertThat(bindings.cancellations).isEqualTo(2);
        assertThat(service.listSessions(user(), null, 20).items()).isEmpty();
    }

    @Test
    void deleteSessionsRejectsAnyActiveRunBeforeMutating() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", "tenant1", "user1", "first", "ACTIVE", "web", now, now));
        sessions.save(new ChatSession("session2", "tenant1", "user1", "second", "ACTIVE", "web", now, now));
        CountingRuntimeBindingService bindings = new CountingRuntimeBindingService();
        SessionApplicationService service = service(sessions, messages, new GuardChatRunService(true), bindings);

        assertThatThrownBy(() -> service.deleteSessions(user(), List.of("session1", "session2")))
                .isInstanceOf(ActiveRunExistsException.class);
        assertThat(sessions.findById("session1").orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(sessions.findById("session2").orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(bindings.cancellations).isZero();
    }

    private MessagePair completeTurn(TestFixture fixture, String userText, String assistantText, String runId) {
        ChatRunMessagePlan plan = fixture.service.prepareRunMessage(user(),
                command(userText, ChatRunMode.NEXT, null, null, null), fixture.session, runId, List.of());
        ChatMessage assistant = fixture.service.saveAssistantMessage("tenant1", "user1", fixture.session,
                assistantText, runId, plan.userMessage().id(), null);
        return new MessagePair(plan.userMessage(), assistant);
    }

    private ChatCommand command(String message, ChatRunMode mode, String parentMessageId,
                                String editedMessageId, String regeneratedMessageId) {
        return new ChatCommand("cmd", "tenant1", "user1", "session1", null, "web", message, List.of(), Map.of(),
                mode, parentMessageId, editedMessageId, regeneratedMessageId);
    }

    private TestFixture fixture() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        ChatSession session = sessions.save(new ChatSession("session1", "tenant1", "user1", "title", "ACTIVE", "web",
                null, "session1", null, null, 0L, null, Instant.now(), Instant.now()));
        return new TestFixture(service(sessions, messages), sessions, session);
    }

    private SessionApplicationService service(InMemorySessionRepository sessions, InMemoryMessageRepository messages) {
        return new SessionApplicationService(
                sessions,
                messages,
                new IncrementingIdGenerator(),
                new PermissionChecker()
        );
    }

    private SessionApplicationService service(InMemorySessionRepository sessions, InMemoryMessageRepository messages,
                                              ChatRunApplicationService chatRunService,
                                              RuntimeBindingApplicationService bindingService) {
        return new SessionApplicationService(
                sessions,
                messages,
                new IncrementingIdGenerator(),
                new PermissionChecker(),
                chatRunService,
                bindingService
        );
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private record TestFixture(SessionApplicationService service, InMemorySessionRepository sessions,
                               ChatSession session) {}

    private record MessagePair(ChatMessage user, ChatMessage assistant) {}

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
            return new ChatSessionPage(findByTenantIdAndUserId(tenantId, userId).stream()
                    .filter(session -> !"DELETED".equals(session.status()))
                    .toList(), null);
        }

        @Override
        public ChatSession save(ChatSession session) {
            sessions.put(session.id(), session);
            return session;
        }

        @Override
        public long nextNodeOrder(String tenantId, String userId, String sessionId) {
            ChatSession session = findByTenantIdAndUserIdAndId(tenantId, userId, sessionId).orElseThrow();
            long next = (session.lastNodeOrder() == null ? 0L : session.lastNodeOrder()) + 1;
            sessions.put(sessionId, new ChatSession(session.id(), session.tenantId(), session.userId(), session.title(),
                    session.status(), session.channel(), session.currentLeafMessageId(), session.rootSessionId(),
                    session.branchSourceSessionId(), session.branchSourceMessageId(), next, session.metadataJson(),
                    session.createdAt(), Instant.now()));
            return next;
        }

        @Override
        public void updateCurrentLeaf(String tenantId, String userId, String sessionId, String leafMessageId) {
            ChatSession session = findByTenantIdAndUserIdAndId(tenantId, userId, sessionId).orElseThrow();
            sessions.put(sessionId, new ChatSession(session.id(), session.tenantId(), session.userId(), session.title(),
                    session.status(), session.channel(), leafMessageId, session.rootSessionId(),
                    session.branchSourceSessionId(), session.branchSourceMessageId(), session.lastNodeOrder(),
                    session.metadataJson(), session.createdAt(), Instant.now()));
        }
    }

    private static class GuardChatRunService extends ChatRunApplicationService {
        private final boolean activeRunExists;

        GuardChatRunService(boolean activeRunExists) {
            super(null, null, null, null, new PermissionChecker(), null);
            this.activeRunExists = activeRunExists;
        }

        @Override
        public void rejectIfActiveRunExists(UserContext user, String sessionId) {
            if (activeRunExists) {
                throw new ActiveRunExistsException(sessionId, "run1");
            }
        }
    }

    private static class CountingRuntimeBindingService extends RuntimeBindingApplicationService {
        private int cancellations;

        CountingRuntimeBindingService() {
            super(null, null, null, Duration.ofDays(3), "relay");
        }

        @Override
        public void cancelActive(String tenantId, String userId, String sessionId) {
            cancellations++;
        }
    }

    private static class InMemoryMessageRepository implements ChatMessageRepository {
        private final Map<String, ChatMessage> messages = new LinkedHashMap<>();
        private final List<ChatMessageAttachment> attachments = new ArrayList<>();

        @Override
        public ChatMessage save(ChatMessage message) {
            messages.put(message.id(), message);
            return message;
        }

        @Override
        public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
            return messages.values().stream()
                    .filter(message -> tenantId.equals(message.tenantId()))
                    .filter(message -> userId.equals(message.userId()))
                    .filter(message -> sessionId.equals(message.sessionId()))
                    .sorted(Comparator.comparing(ChatMessage::createdAt).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public Map<String, ChatMessage> findFirstAssistantMessagesBySessionIds(
                String tenantId, String userId, List<String> sessionIds) {
            return sessionIds.stream()
                    .distinct()
                    .map(sessionId -> messages.values().stream()
                            .filter(message -> tenantId.equals(message.tenantId()))
                            .filter(message -> userId.equals(message.userId()))
                            .filter(message -> sessionId.equals(message.sessionId()))
                            .filter(message -> "assistant".equals(message.role()))
                            .min(Comparator.comparing(ChatMessage::nodeOrder, Comparator.nullsLast(Long::compareTo))
                                    .thenComparing(ChatMessage::createdAt))
                            .orElse(null))
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toMap(ChatMessage::sessionId, message -> message));
        }

        @Override
        public ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String cursor, int limit) {
            return pageMessages(tenantId, userId, sessionId, null, cursor, limit);
        }

        @Override
        public ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String leafMessageId,
                                            String cursor, int limit) {
            List<ChatMessage> items = messages.values().stream()
                    .filter(message -> tenantId.equals(message.tenantId()))
                    .filter(message -> userId.equals(message.userId()))
                    .filter(message -> sessionId.equals(message.sessionId()))
                    .sorted(Comparator.comparing(ChatMessage::nodeOrder, Comparator.nullsLast(Long::compareTo))
                            .thenComparing(ChatMessage::createdAt))
                    .limit(limit)
                    .toList();
            return new ChatMessagePage(items, null);
        }

        @Override
        public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
            return Optional.ofNullable(messages.get(messageId))
                    .filter(message -> tenantId.equals(message.tenantId()))
                    .filter(message -> userId.equals(message.userId()));
        }

        @Override
        public List<ChatMessage> findSiblings(String tenantId, String userId, String sessionId,
                                              String parentMessageId, String role) {
            return messages.values().stream()
                    .filter(message -> tenantId.equals(message.tenantId()))
                    .filter(message -> userId.equals(message.userId()))
                    .filter(message -> sessionId.equals(message.sessionId()))
                    .filter(message -> Objects.equals(parentMessageId, message.parentMessageId()))
                    .filter(message -> Objects.equals(role, message.role()))
                    .sorted(Comparator.comparing(ChatMessage::siblingIndex, Comparator.nullsLast(Integer::compareTo)))
                    .toList();
        }

        @Override
        public int countSiblings(String tenantId, String userId, String sessionId, String parentMessageId, String role) {
            return findSiblings(tenantId, userId, sessionId, parentMessageId, role).size();
        }

        @Override
        public List<ChatMessage> findPathToMessage(String tenantId, String userId, String sessionId, String leafMessageId) {
            List<ChatMessage> path = new ArrayList<>();
            ChatMessage current = findByOwnerAndId(tenantId, userId, leafMessageId)
                    .filter(message -> sessionId.equals(message.sessionId()))
                    .orElse(null);
            while (current != null) {
                path.addFirst(current);
                String parentMessageId = current.parentMessageId();
                current = parentMessageId == null ? null : messages.get(parentMessageId);
            }
            return path;
        }

        @Override
        public ChatMessageAttachment saveAttachment(ChatMessageAttachment attachment) {
            attachments.add(attachment);
            return attachment;
        }

        @Override
        public List<ChatMessageAttachment> findAttachments(String tenantId, String userId, String messageId) {
            return attachments.stream()
                    .filter(attachment -> tenantId.equals(attachment.tenantId()))
                    .filter(attachment -> userId.equals(attachment.userId()))
                    .filter(attachment -> messageId.equals(attachment.messageId()))
                    .toList();
        }
    }

    private static class IncrementingIdGenerator implements IdGenerator {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public String newId(String prefix, IdGenerateContext context) {
            return prefix + "_" + counter.incrementAndGet();
        }
    }
}
