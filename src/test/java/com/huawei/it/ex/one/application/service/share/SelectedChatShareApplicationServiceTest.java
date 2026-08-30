/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.ChatShareSelectedMessagesProperties;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.memory.ChatMessagePageQuery;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.integration.share.ChatShareRepository;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessageAttachment;
import com.huawei.it.ex.one.domain.chat.ChatMessagePage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatSessionNumberPage;
import com.huawei.it.ex.one.domain.chat.ChatSessionPage;
import com.huawei.it.ex.one.domain.chat.ChatShare;
import com.huawei.it.ex.one.domain.chat.ChatSharePage;
import com.huawei.it.ex.one.domain.chat.ChatShareSummary;
import com.huawei.it.ex.one.infrastructure.share.DefaultChatShareAccessPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

class SelectedChatShareApplicationServiceTest {
    @Test
    void createsUserOnlySnapshotForFailedTurn() {
        Fixture fixture = fixture();

        ChatShare share = fixture.service.create(user(), new CreateSelectedChatShareCommand(
                "session1", List.of(" failed_user "), null, null));

        assertThat(share.scope()).isEqualTo("SELECTED_MESSAGES");
        assertThat(share.sourceUserMessageId()).isEqualTo("failed_user");
        assertThat(share.sourceAssistantMessageId()).isNull();
        assertThat(share.sourceRunId()).isNull();
        assertThat(share.title()).isEqualTo("本轮执行失败");
        assertThat(share.snapshot().question()).isNull();
        assertThat(share.snapshot().answer()).isNull();
        assertThat(share.snapshot().parts()).isEmpty();
        assertThat(share.snapshot().messages()).singleElement()
                .satisfies(message -> {
                    assertThat(message.messageId()).isEqualTo("failed_user");
                    assertThat(message.role()).isEqualTo("user");
                    assertThat(message.parts()).isEmpty();
                });
    }

    @Test
    void createsAssistantOnlySnapshotWithVisibleParts() {
        Fixture fixture = fixture();

        ChatShare share = fixture.service.create(user(), new CreateSelectedChatShareCommand(
                "session1", List.of("assistant2"), null, null));

        assertThat(share.sourceUserMessageId()).isNull();
        assertThat(share.sourceAssistantMessageId()).isEqualTo("assistant2");
        assertThat(share.snapshot().messages()).singleElement()
                .satisfies(message -> {
                    assertThat(message.messageId()).isEqualTo("assistant2");
                    assertThat(message.parts()).extracting(part -> part.partId())
                            .containsExactly("part_visible");
                });
    }

    @Test
    void ordersExplicitSelectionByPathWithoutFillingIntermediateMessages() {
        Fixture fixture = fixture();

        ChatShare share = fixture.service.create(user(), new CreateSelectedChatShareCommand(
                "session1", List.of("assistant2", "user1", "assistant2"), "  路径分享  ", null));

        assertThat(share.title()).isEqualTo("路径分享");
        assertThat(share.sourceUserMessageId()).isEqualTo("user1");
        assertThat(share.sourceAssistantMessageId()).isEqualTo("assistant2");
        assertThat(share.snapshot().messages()).extracting(message -> message.messageId())
                .containsExactly("user1", "assistant2");
        assertThat(share.snapshot().messages().getFirst().attachments())
                .extracting(attachment -> attachment.documentId())
                .containsExactly("doc1");
        assertThat(fixture.messages.attachmentBatchCalls()).isEqualTo(1);
        assertThat(fixture.messages.partBatchCalls()).isEqualTo(1);
    }

    @Test
    void safelyTruncatesSelectedShareTitleToUtf8ColumnLimit() {
        Fixture fixture = fixture();

        ChatShare share = fixture.service.create(user(), new CreateSelectedChatShareCommand(
                "session1", List.of("assistant2"), "中".repeat(86), null));

        assertThat(share.title()).isEqualTo("中".repeat(85));
        assertThat(share.title().getBytes(StandardCharsets.UTF_8)).hasSize(255);
    }

    @Test
    void ignoresBlankIdsAndDeduplicatesTrimmedIds() {
        Fixture fixture = fixture();

        ChatShare share = fixture.service.create(user(), new CreateSelectedChatShareCommand(
                "session1", java.util.Arrays.asList(" assistant2 ", "", null, "assistant2"), null, null));

        assertThat(share.snapshot().messages()).extracting(message -> message.messageId())
                .containsExactly("assistant2");
    }

    @Test
    void rejectsMessagesFromDifferentBranches() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.create(user(), new CreateSelectedChatShareCommand(
                "session1", List.of("user2", "branch_user"), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同一条会话分支");
        assertThat(fixture.shares.saveCount()).isZero();
    }

    @Test
    void rejectsMissingCrossSessionAndUnsupportedMessages() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.create(user(), new CreateSelectedChatShareCommand(
                "session1", List.of("other_session_user"), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或不属于");
        assertThatThrownBy(() -> fixture.service.create(user(), new CreateSelectedChatShareCommand(
                "session1", List.of("system_message"), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user 或 assistant");
    }

    @Test
    void appliesRawMessageCountLimitBeforeDeduplication() {
        Fixture fixture = fixture();
        fixture.properties.setMaxMessages(2);

        assertThatThrownBy(() -> fixture.service.create(user(), new CreateSelectedChatShareCommand(
                "session1", List.of("user1", "user1", "assistant1"), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数量超过限制");
        assertThat(fixture.shares.saveCount()).isZero();
    }

    @Test
    void rejectsOversizedSnapshotBeforeRepositoryWrite() {
        Fixture fixture = fixture();
        fixture.properties.setMaxSnapshotBytes(1L);

        assertThatThrownBy(() -> fixture.service.create(user(), new CreateSelectedChatShareCommand(
                "session1", List.of("user1"), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("快照大小超过限制");
        assertThat(fixture.shares.saveCount()).isZero();
    }

    private Fixture fixture() {
        Instant now = Instant.parse("2026-08-02T10:00:00Z");
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        sessions.save(new ChatSession("session1", "tenant1", "user1", "测试会话", "ACTIVE", "web",
                "failed_user", "session1", null, null, 7L, null, now, now));
        sessions.save(new ChatSession("session2", "tenant1", "user1", "其他会话", "ACTIVE", "web",
                "other_session_user", "session2", null, null, 1L, null, now, now));

        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        messages.save(message("user1", "session1", null, 1L, 0, "user", "第一个问题", "run1", now));
        messages.save(message("assistant1", "session1", "user1", 2L, 1, "assistant",
                "第一个回答", "run1", now.plusSeconds(1)));
        messages.save(message("user2", "session1", "assistant1", 3L, 2, "user",
                "第二个问题", "run2", now.plusSeconds(2)));
        messages.save(message("assistant2", "session1", "user2", 4L, 3, "assistant",
                "第二个回答", "run2", now.plusSeconds(3)));
        messages.save(message("failed_user", "session1", "assistant2", 5L, 4, "user",
                "本轮执行失败", "run3", now.plusSeconds(4)));
        messages.save(message("branch_user", "session1", "assistant1", 6L, 2, "user",
                "另一分支", "run4", now.plusSeconds(5)));
        messages.save(message("system_message", "session1", "assistant2", 7L, 4, "system",
                "系统消息", "run5", now.plusSeconds(6)));
        messages.save(message("other_session_user", "session2", null, 1L, 0, "user",
                "其他会话", "run6", now));
        messages.saveAttachment(new ChatMessageAttachment("attachment1", "tenant1", "user1", "session1",
                "user1", "doc1", 1, "invoice.pdf", "application/pdf", 100L, null, now));
        messages.savePart(part("part_visible", "assistant2", true, 1));
        messages.savePart(part("part_hidden", "assistant2", false, 2));

        InMemoryShareRepository shares = new InMemoryShareRepository();
        ChatShareSelectedMessagesProperties properties = new ChatShareSelectedMessagesProperties();
        SelectedChatShareApplicationService service = new SelectedChatShareApplicationService(
                shares,
                messages,
                sessions,
                new IncrementingIdGenerator(),
                new PermissionChecker(),
                new DefaultChatShareAccessPolicy(),
                properties,
                new ObjectMapper().findAndRegisterModules()
        );
        return new Fixture(service, shares, messages, properties);
    }

    private ChatMessage message(String id, String sessionId, String parentId, long nodeOrder, int depth,
                                String role, String content, String runId, Instant createdAt) {
        return new ChatMessage(id, "tenant1", "user1", sessionId, parentId, nodeOrder, depth, 1,
                role, content, null, runId, "NORMAL", false, null, null, null, null,
                null, createdAt);
    }

    private ChatMessagePart part(String id, String messageId, boolean visible, int order) {
        return new ChatMessagePart(id, "tenant1", "user1", "session1", messageId, "run2",
                "CARD", "card", "卡片", "卡片", "INFO", "card", "inline", visible,
                Map.of("value", id), order, Instant.parse("2026-08-02T10:00:00Z").plusSeconds(order));
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private record Fixture(
            SelectedChatShareApplicationService service,
            InMemoryShareRepository shares,
            InMemoryMessageRepository messages,
            ChatShareSelectedMessagesProperties properties) {}

    private static class InMemoryShareRepository implements ChatShareRepository {
        private final Map<String, ChatShare> shares = new LinkedHashMap<>();
        private int saveCount;

        @Override
        public ChatShare save(ChatShare share) {
            saveCount++;
            shares.put(share.id(), share);
            return share;
        }

        @Override
        public Optional<ChatShare> findById(String shareId) {
            return Optional.ofNullable(shares.get(shareId));
        }

        @Override
        public ChatSharePage pageByOwner(String tenantId, String ownerUserId, int curPage, int pageSize) {
            List<ChatShareSummary> items = shares.values().stream().map(ChatShareSummary::from).toList();
            return new ChatSharePage(items, curPage, pageSize, shares.size(),
                    shares.isEmpty() ? 0 : 1);
        }

        @Override
        public void revokeActiveBySession(String tenantId, String ownerUserId, String sessionId, Instant revokedAt) {
            // 当前测试只验证创建路径。
        }

        int saveCount() {
            return saveCount;
        }
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
        public ChatSessionNumberPage pageNumberByTenantIdAndUserId(
                String tenantId, String userId, int curPage, int pageSize) {
            List<ChatSession> items = findByTenantIdAndUserId(tenantId, userId);
            return new ChatSessionNumberPage(items, curPage, pageSize, items.size(), items.isEmpty() ? 0 : 1);
        }

        @Override
        public ChatSession save(ChatSession session) {
            sessions.put(session.id(), session);
            return session;
        }
    }

    private static class InMemoryMessageRepository implements ChatMessageRepository {
        private final Map<String, ChatMessage> messages = new LinkedHashMap<>();
        private final Map<String, List<ChatMessageAttachment>> attachments = new HashMap<>();
        private final Map<String, List<ChatMessagePart>> parts = new HashMap<>();
        private int attachmentBatchCalls;
        private int partBatchCalls;

        @Override
        public ChatMessage save(ChatMessage message) {
            messages.put(message.id(), message);
            return message;
        }

        @Override
        public ChatMessageAttachment saveAttachment(ChatMessageAttachment attachment) {
            attachments.computeIfAbsent(attachment.messageId(), ignored -> new ArrayList<>()).add(attachment);
            return attachment;
        }

        @Override
        public ChatMessagePart savePart(ChatMessagePart part) {
            parts.computeIfAbsent(part.messageId(), ignored -> new ArrayList<>()).add(part);
            return part;
        }

        @Override
        public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
            return findAllMessageNodesBySession(tenantId, userId, sessionId).stream().limit(limit).toList();
        }

        @Override
        public ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String cursor, int limit) {
            return new ChatMessagePage(findRecentMessages(tenantId, userId, sessionId, limit), null);
        }

        @Override
        public ChatMessagePage pageMessages(ChatMessagePageQuery query) {
            return pageMessages(query.tenantId(), query.userId(), query.sessionId(), query.cursor(), query.limit());
        }

        @Override
        public List<ChatMessage> findAllMessageNodesBySession(String tenantId, String userId, String sessionId) {
            return messages.values().stream()
                    .filter(message -> tenantId.equals(message.tenantId()))
                    .filter(message -> userId.equals(message.userId()))
                    .filter(message -> sessionId.equals(message.sessionId()))
                    .sorted(Comparator.comparingLong(ChatMessage::nodeOrder))
                    .toList();
        }

        @Override
        public List<ChatMessage> findByOwnerAndSessionAndIds(
                String tenantId, String userId, String sessionId, List<String> messageIds) {
            return findAllMessageNodesBySession(tenantId, userId, sessionId).stream()
                    .filter(message -> messageIds.contains(message.id()))
                    .toList();
        }

        @Override
        public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
            return Optional.ofNullable(messages.get(messageId))
                    .filter(message -> tenantId.equals(message.tenantId()))
                    .filter(message -> userId.equals(message.userId()));
        }

        @Override
        public List<ChatMessage> findPathNodesToMessage(
                String tenantId, String userId, String sessionId, String leafMessageId) {
            List<ChatMessage> path = new ArrayList<>();
            ChatMessage current = messages.get(leafMessageId);
            while (current != null
                    && tenantId.equals(current.tenantId())
                    && userId.equals(current.userId())
                    && sessionId.equals(current.sessionId())) {
                path.add(current);
                current = current.parentMessageId() == null ? null : messages.get(current.parentMessageId());
            }
            Collections.reverse(path);
            return List.copyOf(path);
        }

        @Override
        public List<ChatMessageAttachment> findAttachmentsByMessageIds(
                String tenantId, String userId, String sessionId, List<String> messageIds) {
            attachmentBatchCalls++;
            return messageIds.stream()
                    .flatMap(id -> attachments.getOrDefault(id, List.of()).stream())
                    .toList();
        }

        @Override
        public List<ChatMessagePart> findPartsByMessageIds(
                String tenantId, String userId, String sessionId, List<String> messageIds) {
            partBatchCalls++;
            return messageIds.stream()
                    .flatMap(id -> parts.getOrDefault(id, List.of()).stream())
                    .toList();
        }

        int attachmentBatchCalls() {
            return attachmentBatchCalls;
        }

        int partBatchCalls() {
            return partBatchCalls;
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
