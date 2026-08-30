/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.memory.ChatMessagePageQuery;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.integration.share.ChatShareAccessPolicy;
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
import com.huawei.it.ex.one.domain.chat.ChatShareUnavailableException;
import com.huawei.it.ex.one.infrastructure.share.DefaultChatShareAccessPolicy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

class ChatShareApplicationServiceTest {
    @Test
    void createShareBuildsSingleTurnSnapshotWithVisiblePartsAndAttachments() {
        Fixture fixture = fixture(new DefaultChatShareAccessPolicy());
        ChatShare share = fixture.service.create(user(),
                new CreateChatShareCommand("msg_assistant", "报销分享", Instant.now().plusSeconds(3600)));

        assertThat(share.status()).isEqualTo("ACTIVE");
        assertThat(share.sourceUserMessageId()).isEqualTo("msg_user");
        assertThat(share.sourceAssistantMessageId()).isEqualTo("msg_assistant");
        assertThat(share.snapshot().question().content()).isEqualTo("报销流程是什么");
        assertThat(share.snapshot().answer().content()).isEqualTo("请先提交发票和审批单");
        assertThat(share.snapshot().question().attachments()).hasSize(1);
        assertThat(share.snapshot().parts()).extracting(part -> part.partType())
                .containsExactly("PROGRESS", "CARD", "CARD", "REFERENCE");
        assertThat(share.snapshot().parts().get(1).payload())
                .containsEntry("sourceType", "specificSceneInfo")
                .containsKey("specificSceneInfo");
        assertThat(share.snapshot().parts().get(2).payload())
                .containsEntry("sourceType", "openCard")
                .containsEntry("recommendedQuestions", List.of(
                        Map.of("query", "请展开下一个印章的审核结果？", "id", 1, "metadata", Map.of()),
                        Map.of("query", "请展开下一个文件的审核结果？", "id", 2, "metadata", Map.of())
                ));
        assertThat(share.snapshot().parts().get(3).payload())
                .containsEntry("sourceType", "searchList")
                .containsEntry("metadata", Map.of(
                        "knowLevel", List.of("MIP", "CIP", "IIP"),
                        "knowMapping", List.of(Map.of("type", "MIP", "name", "作业依据"))
                ));
        assertThat(fixture.shares.findById(share.id())).contains(share);
    }

    @Test
    void createShareRejectsUserMessageAndExpiredTime() {
        Fixture fixture = fixture(new DefaultChatShareAccessPolicy());

        assertThatThrownBy(() -> fixture.service.create(user(),
                new CreateChatShareCommand("msg_user", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assistant");
        assertThatThrownBy(() -> fixture.service.create(user(),
                new CreateChatShareCommand("msg_assistant", null, Instant.now().minusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiresAt");
    }

    @Test
    void createShareSafelyTruncatesExplicitAndDefaultChineseTitles() {
        Fixture explicitFixture = fixture(new DefaultChatShareAccessPolicy());
        ChatShare explicitShare = explicitFixture.service.create(user(), new CreateChatShareCommand(
                "msg_assistant", "中".repeat(85) + "a中", null));

        assertThat(explicitShare.title()).isEqualTo("中".repeat(85) + "a");
        assertThat(explicitShare.title().getBytes(StandardCharsets.UTF_8)).hasSize(256);

        Fixture defaultFixture = fixture(new DefaultChatShareAccessPolicy(), "中".repeat(86));
        ChatShare defaultShare = defaultFixture.service.create(user(),
                new CreateChatShareCommand("msg_assistant", null, null));

        assertThat(defaultShare.title()).isEqualTo("中".repeat(85));
        assertThat(defaultShare.title().getBytes(StandardCharsets.UTF_8)).hasSize(255);
    }

    @Test
    void getShareUsesAccessPolicyAndLifecycleChecks() {
        Fixture fixture = fixture(new DefaultChatShareAccessPolicy());
        ChatShare share = fixture.service.create(user(), new CreateChatShareCommand("msg_assistant", null, null));

        assertThat(fixture.service.get(new UserContext("tenant1", "user2", "User Two"), share.id()).id())
                .isEqualTo(share.id());
        assertThatThrownBy(() -> fixture.service.get(new UserContext("tenant2", "user3", "Other Tenant"), share.id()))
                .isInstanceOf(SecurityException.class);

        ChatShare revoked = fixture.service.revoke(user(), share.id());
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> fixture.service.get(user(), share.id()))
                .isInstanceOf(ChatShareUnavailableException.class)
                .extracting("code")
                .isEqualTo("SHARE_REVOKED");
        assertThatThrownBy(() -> fixture.service.get(new UserContext("tenant2", "user3", "Other Tenant"), share.id()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void expiredShareReturnsStableCodeOnlyAfterViewPolicyPasses() {
        Fixture fixture = fixture(new DefaultChatShareAccessPolicy());
        ChatShare share = fixture.service.create(user(), new CreateChatShareCommand("msg_assistant", null, null));
        fixture.shares.save(new ChatShare(share.id(), share.tenantId(), share.ownerUserId(), share.sourceSessionId(),
                share.sourceUserMessageId(), share.sourceAssistantMessageId(), share.sourceRunId(), share.title(),
                share.scope(), share.visibility(), share.status(), Instant.now().minusSeconds(1), null,
                share.snapshot(), share.createdAt(), Instant.now()));

        assertThatThrownBy(() -> fixture.service.get(user(), share.id()))
                .isInstanceOf(ChatShareUnavailableException.class)
                .extracting("code")
                .isEqualTo("SHARE_EXPIRED");
        assertThatThrownBy(() -> fixture.service.get(new UserContext("tenant2", "user3", "Other Tenant"), share.id()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void enterprisePolicyCanOverrideCreateViewAndRevoke() {
        ChatShareAccessPolicy denyAll = new ChatShareAccessPolicy() {
            @Override
            public boolean canCreate(UserContext user, ChatMessage sourceMessage) {
                return false;
            }

            @Override
            public boolean canView(UserContext user, ChatShare share) {
                return false;
            }

            @Override
            public boolean canRevoke(UserContext user, ChatShare share) {
                return false;
            }
        };
        Fixture fixture = fixture(denyAll);

        assertThatThrownBy(() -> fixture.service.create(user(),
                new CreateChatShareCommand("msg_assistant", null, null)))
                .isInstanceOf(SecurityException.class);
    }

    private Fixture fixture(ChatShareAccessPolicy policy) {
        return fixture(policy, "报销流程是什么");
    }

    private Fixture fixture(ChatShareAccessPolicy policy, String question) {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryShareRepository shares = new InMemoryShareRepository();
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", "tenant1", "user1", "报销", "ACTIVE", "web",
                "msg_assistant", "session1", null, null, 2L, null, now, now));
        messages.save(new ChatMessage("msg_user", "tenant1", "user1", "session1", null, 1L,
                0, 1, "user", question, null, "run1", "NORMAL", false,
                null, null, null, null, null, now));
        messages.save(new ChatMessage("msg_assistant", "tenant1", "user1", "session1", "msg_user",
                2L, 1, 1, "assistant", "请先提交发票和审批单", null, "run1", "NORMAL",
                false, null, null, null, null, null,
                List.of(
                        part("part_visible", true, "PROGRESS"),
                        specificSceneInfoPart(),
                        recommendedQuestionsPart(),
                        searchListPart(),
                        part("part_hidden", false, "RUNTIME_EVENT")
                ), now.plusSeconds(1)));
        messages.saveAttachment(new ChatMessageAttachment("att1", "tenant1", "user1", "session1",
                "msg_user", "doc1", 1, "invoice.pdf", "application/pdf", 1024L, null, now));
        ChatShareApplicationService service = new ChatShareApplicationService(
                shares, messages, sessions, new IncrementingIdGenerator(), new PermissionChecker(), policy);
        return new Fixture(service, shares);
    }

    private ChatMessagePart part(String id, Boolean visible, String partType) {
        return new ChatMessagePart(id, "tenant1", "user1", "session1", "msg_assistant", "run1",
                partType, "source", partType + " text", partType, "INFO", "runtime",
                visible ? "inline" : "debug", visible, Map.of("value", partType), 1, Instant.now());
    }

    private ChatMessagePart specificSceneInfoPart() {
        return new ChatMessagePart("part_specific_scene", "tenant1", "user1", "session1",
                "msg_assistant", "run1", "CARD", "specificSceneInfo", "specificSceneInfo",
                "卡片展示", "INFO", "card", "inline", true, Map.of(
                        "source", "domain-agent",
                        "sourceType", "specificSceneInfo",
                        "cardType", "specificSceneInfo",
                        "cardSources", List.of("specificSceneInfo"),
                        "specificSceneInfo", List.of(Map.of("type", "authorization"))
                ), 2, Instant.now());
    }

    private ChatMessagePart recommendedQuestionsPart() {
        return new ChatMessagePart("part_recommended_questions", "tenant1", "user1", "session1",
                "msg_assistant", "run1", "CARD", "openCard", "openCard",
                "卡片展示", "INFO", "card", "inline", true, Map.of(
                        "source", "domain-agent",
                        "sourceType", "openCard",
                        "cardType", "openCard",
                        "cardSources", List.of("openCard"),
                        "openCard", "Y",
                        "recommendedQuestions", List.of(
                                Map.of("query", "请展开下一个印章的审核结果？", "id", 1, "metadata", Map.of()),
                                Map.of("query", "请展开下一个文件的审核结果？", "id", 2, "metadata", Map.of())
                        )
                ), 3, Instant.now());
    }

    private ChatMessagePart searchListPart() {
        return new ChatMessagePart("part_search_list", "tenant1", "user1", "session1",
                "msg_assistant", "run1", "REFERENCE", "searchList", "search_list",
                "引用", "INFO", "reference", "inline", true, Map.of(
                        "source", "domain-agent",
                        "sourceType", "searchList",
                        "referenceType", "search_list",
                        "references", List.of(Map.of("title", "任命通知（子公司CFO）")),
                        "metadata", Map.of(
                                "knowLevel", List.of("MIP", "CIP", "IIP"),
                                "knowMapping", List.of(Map.of("type", "MIP", "name", "作业依据"))
                        )
                ), 4, Instant.now());
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private record Fixture(ChatShareApplicationService service, InMemoryShareRepository shares) {}

    private static class InMemoryShareRepository implements ChatShareRepository {
        private final Map<String, ChatShare> shares = new LinkedHashMap<>();

        @Override
        public ChatShare save(ChatShare share) {
            shares.put(share.id(), share);
            return share;
        }

        @Override
        public Optional<ChatShare> findById(String shareId) {
            return Optional.ofNullable(shares.get(shareId));
        }

        @Override
        public ChatSharePage pageByOwner(String tenantId, String ownerUserId, int curPage, int pageSize) {
            List<ChatShareSummary> items = shares.values().stream()
                    .filter(share -> tenantId.equals(share.tenantId()))
                    .filter(share -> ownerUserId.equals(share.ownerUserId()))
                    .map(ChatShareSummary::from)
                    .toList();
            return new ChatSharePage(items, curPage, pageSize, items.size(), items.isEmpty() ? 0 : 1);
        }

        @Override
        public void revokeActiveBySession(String tenantId, String ownerUserId, String sessionId, Instant revokedAt) {
            shares.replaceAll((id, share) -> tenantId.equals(share.tenantId())
                    && ownerUserId.equals(share.ownerUserId())
                    && sessionId.equals(share.sourceSessionId())
                    && "ACTIVE".equals(share.status())
                    ? share.revoke(revokedAt)
                    : share);
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
        public ChatSessionNumberPage pageNumberByTenantIdAndUserId(String tenantId, String userId, int curPage, int pageSize) {
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
        public ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String cursor, int limit) {
            return pageMessages(new ChatMessagePageQuery(tenantId, userId, sessionId, null, cursor, limit));
        }

        @Override
        public ChatMessagePage pageMessages(ChatMessagePageQuery query) {
            return new ChatMessagePage(findRecentMessages(query.tenantId(), query.userId(),
                    query.sessionId(), query.limit()), null);
        }

        @Override
        public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
            return Optional.ofNullable(messages.get(messageId))
                    .filter(message -> tenantId.equals(message.tenantId()))
                    .filter(message -> userId.equals(message.userId()));
        }

        @Override
        public ChatMessageAttachment saveAttachment(ChatMessageAttachment attachment) {
            attachments.computeIfAbsent(attachment.messageId(), ignored -> new java.util.ArrayList<>()).add(attachment);
            return attachment;
        }

        @Override
        public List<ChatMessageAttachment> findAttachments(String tenantId, String userId, String messageId) {
            return attachments.getOrDefault(messageId, List.of()).stream()
                    .filter(attachment -> tenantId.equals(attachment.tenantId()))
                    .filter(attachment -> userId.equals(attachment.userId()))
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
