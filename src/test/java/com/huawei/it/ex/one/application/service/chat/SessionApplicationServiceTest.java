package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.memory.ChatMessagePageQuery;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.integration.share.ChatShareRepository;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessageAttachment;
import com.huawei.it.ex.one.domain.chat.ChatMessagePage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatMessagePartDraft;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatSessionNumberPage;
import com.huawei.it.ex.one.domain.chat.ChatSessionPage;
import com.huawei.it.ex.one.domain.chat.ChatShare;
import com.huawei.it.ex.one.domain.chat.ChatSharePage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

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
class SessionApplicationServiceTest {
    @Test
    void appTagIsNormalizedCreatedAndValidatedForExistingSession() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        SessionApplicationService service = service(sessions, messages);

        ChatSession created = service.createSession(user(), "资金分析", "web", " fund-app ", " 资金助手 ");

        assertThat(created.appId()).isEqualTo("fund-app");
        assertThat(created.appName()).isEqualTo("资金助手");
        ChatCommand matching = new ChatCommand("cmd", "tenant1", "user1", created.id(), null, "web",
                "继续提问", List.of(), Map.of(), null, null, ChatRunMode.NEXT, null, null, null,
                null, null, null, null, Map.of(), "fund-app", null);
        assertThat(service.loadOrCreate(matching).id()).isEqualTo(created.id());

        ChatCommand mismatched = new ChatCommand("cmd", "tenant1", "user1", created.id(), null, "web",
                "错误分组", List.of(), Map.of(), null, null, ChatRunMode.NEXT, null, null, null,
                null, null, null, null, Map.of(), "tax-app", "税务助手");
        assertThatThrownBy(() -> service.loadOrCreate(mismatched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appId 与已有会话不一致");
        assertThat(messages.messages).isEmpty();
    }

    @Test
    void appNameWithoutAppIdIsRejected() {
        SessionApplicationService service = service(new InMemorySessionRepository(), new InMemoryMessageRepository());

        assertThatThrownBy(() -> service.createSession(user(), "资金分析", "web", null, "资金助手"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appName 不能脱离 appId");
    }

    @Test
    void messageReadWatermarksAreMonotonicClampedAndDoNotReorderSession() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        Instant createdAt = Instant.parse("2026-07-14T01:00:00Z");
        Instant updatedAt = Instant.parse("2026-07-14T02:00:00Z");
        ChatSession session = sessions.save(new ChatSession(
                "session1", "tenant1", "user1", "title", "ACTIVE", "web",
                null, null, null, "session1", null, null, 0L,
                12L, 4L, null, createdAt, updatedAt));
        SessionApplicationService service = service(sessions, messages);

        ChatSession readEight = service.markSessionRead(user(), session.id(), 8L);
        ChatSession staleRead = service.markSessionRead(user(), session.id(), 6L);
        service.advanceLatestMessageSeq(user(), staleRead, 20L);
        ChatSession oversizedRead = service.markSessionRead(user(), session.id(), 999L);

        assertThat(readEight.lastReadSeq()).isEqualTo(8L);
        assertThat(readEight.hasUnread()).isTrue();
        assertThat(staleRead.lastReadSeq()).isEqualTo(8L);
        assertThat(oversizedRead.latestMessageSeq()).isEqualTo(20L);
        assertThat(oversizedRead.lastReadSeq()).isEqualTo(20L);
        assertThat(oversizedRead.hasUnread()).isFalse();
        assertThat(oversizedRead.updatedAt()).isEqualTo(updatedAt);
        assertThatThrownBy(() -> service.markSessionRead(user(), session.id(), -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readThroughSeq");
    }

    @Test
    void appIdFiltersCursorAndNumberPages() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        Instant now = Instant.now();
        sessions.save(taggedSession("fund-1", "fund-app", "资金助手", now.plusSeconds(1)));
        sessions.save(taggedSession("tax-1", "tax-app", "税务助手", now.plusSeconds(2)));
        sessions.save(new ChatSession("plain-1", "tenant1", "user1", "plain", "ACTIVE", "web", now, now));
        SessionApplicationService service = service(sessions, messages);

        assertThat(service.listSessions(user(), "fund-app", null, 20).items())
                .extracting(ChatSession::id)
                .containsExactly("fund-1");
        ChatSessionNumberPage page = service.listSessionsByPage(user(), "tax-app", 1, 20);
        assertThat(page.items()).extracting(ChatSession::id).containsExactly("tax-1");
        assertThat(page.totalRows()).isEqualTo(1);
        assertThat(service.listSessions(user(), null, 20).items())
                .extracting(ChatSession::id)
                .containsExactlyInAnyOrder("fund-1", "tax-1", "plain-1");
    }

    @Test
    void branchAndSessionLifecyclePreserveAppTag() {
        TestFixture fixture = fixture("fund-app", "资金助手");
        MessagePair original = completeTurn(fixture, "资金问题", "资金回答", "run1");

        ChatSession branch = fixture.service.createBranch(
                user(), fixture.session.id(), original.assistant().id(), "资金分支");
        ChatSession renamed = fixture.service.renameSession(user(), branch.id(), "重命名分支");
        ChatSession archived = fixture.service.archiveSession(user(), branch.id());
        ChatSession restored = fixture.service.restoreSession(user(), branch.id());

        assertThat(List.of(branch, renamed, archived, restored)).allSatisfy(session -> {
            assertThat(session.appId()).isEqualTo("fund-app");
            assertThat(session.appName()).isEqualTo("资金助手");
        });
    }

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
    void listMessagesReturnsMessageAttachments() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", "tenant1", "user1", "title", "ACTIVE", "web", now, now));
        messages.save(new ChatMessage("msg1", "tenant1", "user1", "session1", "user", "带附件的问题", null, now));
        messages.saveAttachment(new ChatMessageAttachment("att1", "tenant1", "user1", "session1", "msg1",
                "doc1", 1, "report.pdf", "application/pdf", 1024L, null, now));

        SessionApplicationService service = service(sessions, messages);

        List<ChatMessage> history = service.listMessages(user(), "session1", null, 50).items();

        assertThat(history).hasSize(1);
        assertThat(history.getFirst().attachments()).hasSize(1);
        assertThat(history.getFirst().attachments().getFirst().documentId()).isEqualTo("doc1");
        assertThat(history.getFirst().attachments().getFirst().name()).isEqualTo("report.pdf");
    }

    @Test
    void normalRunCreatesUserAndAssistantAsActivePath() {
        TestFixture fixture = fixture();
        ChatRunMessagePlan plan = fixture.service.prepareRunMessage(user(), command("hello", ChatRunMode.NEXT,
                null, null, null), fixture.session, "run1", List.of());

        ChatMessage assistant = saveAssistant(fixture, "world", "run1", plan.userMessage().id(), null);
        List<ChatMessage> activePath = fixture.service.listMessages(user(), fixture.session.id(), null, 50).items();

        assertThat(plan.userMessage().parentMessageId()).isNull();
        assertThat(assistant.parentMessageId()).isEqualTo(plan.userMessage().id());
        assertThat(activePath).extracting(ChatMessage::role).containsExactly("user", "assistant");
        assertThat(fixture.sessions.findById(fixture.session.id()).orElseThrow().currentLeafMessageId())
                .isEqualTo(assistant.id());
    }

    @Test
    void attachmentOnlyNextStoresEmptyUserContent() {
        TestFixture fixture = fixture();
        AttachmentRef attachment = new AttachmentRef("doc1", "财务报表.pdf", "application/pdf", 1L);
        ChatCommand command = new ChatCommand("cmd", "tenant1", "user1", "session1", null, "web", "",
                List.of(attachment), Map.of(), ChatRunMode.NEXT, null, null, null);

        ChatRunMessagePlan plan = fixture.service.prepareRunMessage(
                user(), command, fixture.session, "run1", List.of(attachment));

        assertThat(plan.userMessage().content()).isEmpty();
    }

    @Test
    void attachmentOnlyEditStillRequiresText() {
        TestFixture fixture = fixture();
        MessagePair original = completeTurn(fixture, "原始问题", "原始回答", "run1");
        AttachmentRef attachment = new AttachmentRef("doc1", "财务报表.pdf", "application/pdf", 1L);
        ChatCommand command = new ChatCommand("cmd", "tenant1", "user1", "session1", null, "web", "",
                List.of(attachment), Map.of(), ChatRunMode.EDIT_USER, null, original.user().id(), null);

        assertThatThrownBy(() -> fixture.service.prepareRunMessage(
                user(), command, fixture.session, "run2", List.of(attachment)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户消息不能为空");
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
        ChatMessage regenerated = saveAssistant(fixture, "第二次回答", "run2",
                regeneratePlan.userMessage().id(), regeneratePlan.regeneratedFromMessageId());

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
        RecordingShareRepository shares = new RecordingShareRepository();
        SessionApplicationService service = service(sessions, messages, null, bindings, shares);

        ChatSession deleted = service.deleteSession(user(), session.id());

        assertThat(deleted.status()).isEqualTo("DELETED");
        assertThat(bindings.cancellations).isEqualTo(1);
        assertThat(shares.revokedSessions).containsExactly("session1");
        assertThat(service.listSessions(user(), null, 20).items()).isEmpty();
        assertThatThrownBy(() -> service.getSession(user(), session.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    void deleteSessionStopsActiveRunAfterSoftDelete() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        ChatSession session = sessions.save(new ChatSession("session1", "tenant1", "user1", "title", "ACTIVE", "web",
                Instant.now(), Instant.now()));
        CountingRuntimeBindingService bindings = new CountingRuntimeBindingService();
        CountingStopCoordinator stopCoordinator = new CountingStopCoordinator();
        SessionApplicationService service = service(sessions, messages, activeRunService("session1"), bindings, null,
                stopCoordinator);

        ChatSession deleted = service.deleteSession(user(), session.id());

        assertThat(deleted.status()).isEqualTo("DELETED");
        assertThat(stopCoordinator.stoppedSessions).containsExactly("session1");
        assertThat(stopCoordinator.stoppedRuns).containsExactly("run-session1");
        assertThat(bindings.cancellations).isEqualTo(1);
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
    void listSessionsByPageReturnsTotalRowsAndExcludesDeletedSessions() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        Instant now = Instant.now();
        ChatSession first = sessions.save(new ChatSession("session1", "tenant1", "user1", "first", "ACTIVE", "web",
                now, now.plusSeconds(1)));
        ChatSession second = sessions.save(new ChatSession("session2", "tenant1", "user1", "second", "ARCHIVED", "web",
                now, now.plusSeconds(2)));
        sessions.save(new ChatSession("session3", "tenant1", "user1", "deleted", "DELETED", "web",
                now, now.plusSeconds(3)));
        sessions.save(new ChatSession("session4", "tenant1", "user2", "other", "ACTIVE", "web",
                now, now.plusSeconds(4)));
        messages.save(new ChatMessage("msg1", "tenant1", "user1", first.id(), null, 1L, 0, 1,
                "assistant", "第一条回答", null, "run1", "NORMAL", false, null, null, null, null, null, now));
        messages.save(new ChatMessage("msg2", "tenant1", "user1", second.id(), null, 1L, 0, 1,
                "assistant", "归档回答", null, "run2", "NORMAL", false, null, null, null, null, null, now));
        SessionApplicationService service = service(sessions, messages);

        ChatSessionNumberPage page = service.listSessionsByPage(user(), 1, 1);
        Map<String, String> firstAnswers = service.findFirstAssistantAnswers(user(), page.items());

        assertThat(page.items()).extracting(ChatSession::id).containsExactly("session2");
        assertThat(page.curPage()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(1);
        assertThat(page.totalRows()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(firstAnswers).containsEntry("session2", "归档回答");
    }

    @Test
    void assistantMessagePartsHaveStableDisplaySemantics() {
        TestFixture fixture = fixture();
        ChatRunMessagePlan plan = fixture.service.prepareRunMessage(user(), command("hello", ChatRunMode.NEXT,
                null, null, null), fixture.session, "run1", List.of());

        ChatMessage assistant = fixture.service.saveAssistantMessage(new AssistantMessageSaveCommand(
                "tenant1", "user1", fixture.session, "最终回答", "run1", plan.userMessage().id(), null,
                List.of(
                        new ChatMessagePartDraft("PROGRESS", "relay-progress", "处理中", Map.of("text", "处理中")),
                        new ChatMessagePartDraft("TOOL", "tool_call_streaming", "search: 查询流程",
                                Map.of("toolName", "search", "inputPreview", "查询流程")),
                        new ChatMessagePartDraft("THINKING", "thinking-operation-end", "ENDED: op1",
                                Map.of("status", "ENDED", "operationId", "op1"))
                ), null));

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
        ChatMessagePart answerPart = assistant.parts().getLast();
        assertThat(answerPart.payload()).containsEntry(
                "serverTimestampMs", answerPart.createdAt().toEpochMilli());
    }

    @Test
    void placeholderAssistantDropsRuntimeContentAtTheMessagePersistenceBoundary() {
        TestFixture fixture = fixture();
        ChatRunMessagePlan plan = fixture.service.prepareRunMessage(user(), command("hello", ChatRunMode.NEXT,
                null, null, null), fixture.session, "run1", List.of());
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已按策略隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);

        ChatMessage assistant = fixture.service.saveAssistantMessage(new AssistantMessageSaveCommand(
                "tenant1", "user1", fixture.session, "真实回答", "run1", plan.userMessage().id(), null,
                List.of(
                        new ChatMessagePartDraft("THINKING", "thinking", "真实思考", Map.of()),
                        new ChatMessagePartDraft("CARD", "card", "真实卡片", Map.of()),
                        new ChatMessagePartDraft("INTENT_CLARIFICATION_REQUEST", "intent-clarification",
                                "请选择技能", Map.of("clarifyQuestion", "请选择技能"))
                ), AgentDataPersistenceMetadata.mergeAssistantMetadata(null, state), null, true));

        assertThat(assistant.content()).isEqualTo("回答已按策略隐藏");
        assertThat(assistant.parts()).extracting(ChatMessagePart::partType)
                .containsExactly("INTENT_CLARIFICATION_REQUEST");
        assertThat(AgentDataPersistenceMetadata.placeholderAssistant(assistant.metadataJson())).isTrue();
    }

    @Test
    void aNewRunFullPolicyDoesNotInheritThePreviousRunPlaceholderMarker() {
        TestFixture fixture = fixture();
        ChatRunMessagePlan plan = fixture.service.prepareRunMessage(user(), command("hello", ChatRunMode.NEXT,
                null, null, null), fixture.session, "run1", List.of());
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已按策略隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        ChatMessage placeholder = fixture.service.saveAssistantMessage(new AssistantMessageSaveCommand(
                "tenant1", "user1", fixture.session, "真实回答", "run1", plan.userMessage().id(), null,
                List.of(), AgentDataPersistenceMetadata.mergeAssistantMetadata(null, state), null, true));

        ChatMessage updated = fixture.service.updateAssistantMessage(new AssistantMessageUpdateCommand(
                "tenant1", "user1", fixture.session, placeholder.id(), "新 run 可保存的回答", "run2",
                List.of(new ChatMessagePartDraft("THINKING", "thinking", "可保存思考", Map.of())),
                null, true));

        assertThat(updated.content()).isEqualTo("新 run 可保存的回答");
        assertThat(AgentDataPersistenceMetadata.placeholderAssistant(updated.metadataJson())).isFalse();
        assertThat(updated.parts()).extracting(ChatMessagePart::partType)
                .containsExactly("THINKING", "ANSWER");
    }

    @Test
    void placeholderMarkerWithoutContentNeverFallsBackToRealAnswer() {
        TestFixture fixture = fixture();
        ChatRunMessagePlan plan = fixture.service.prepareRunMessage(user(), command("hello", ChatRunMode.NEXT,
                null, null, null), fixture.session, "run1", List.of());

        ChatMessage assistant = fixture.service.saveAssistantMessage(new AssistantMessageSaveCommand(
                "tenant1", "user1", fixture.session, "真实回答不得落库", "run1",
                plan.userMessage().id(), null, List.of(),
                "{\"agentDataPersistence\":{\"policy\":\"ASSISTANT_PLACEHOLDER\"}}",
                null, true));

        assertThat(assistant.content())
                .isEqualTo("根据数据留存策略，本次回答不在消息历史中展示。");
        assertThat(assistant.content()).doesNotContain("真实回答");
        assertThat(assistant.parts()).isEmpty();
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
        saveAssistant(fixture, "重新生成回答", "run3",
                regeneratePlan.userMessage().id(), regeneratePlan.regeneratedFromMessageId());

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
        SessionApplicationService service = service(sessions, messages, null, bindings);

        List<ChatSession> deleted = service.deleteSessions(user(), List.of("session1", "session2", "session1"));

        assertThat(deleted).extracting(ChatSession::id).containsExactly("session1", "session2");
        assertThat(deleted).extracting(ChatSession::status).containsExactly("DELETED", "DELETED");
        assertThat(bindings.cancellations).isEqualTo(2);
        assertThat(service.listSessions(user(), null, 20).items()).isEmpty();
    }

    @Test
    void deleteSessionsStopsActiveRunsAndDeletesAll() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", "tenant1", "user1", "first", "ACTIVE", "web", now, now));
        sessions.save(new ChatSession("session2", "tenant1", "user1", "second", "ACTIVE", "web", now, now));
        CountingRuntimeBindingService bindings = new CountingRuntimeBindingService();
        CountingStopCoordinator stopCoordinator = new CountingStopCoordinator();
        SessionApplicationService service = service(sessions, messages, activeRunService("session1", "session2"),
                bindings, null, stopCoordinator);

        List<ChatSession> deleted = service.deleteSessions(user(), List.of("session1", "session2"));

        assertThat(deleted).extracting(ChatSession::status).containsExactly("DELETED", "DELETED");
        assertThat(stopCoordinator.stoppedSessions).containsExactly("session1", "session2");
        assertThat(stopCoordinator.stoppedRuns).containsExactly("run-session1", "run-session2");
        assertThat(bindings.cancellations).isEqualTo(2);
    }

    private MessagePair completeTurn(TestFixture fixture, String userText, String assistantText, String runId) {
        ChatRunMessagePlan plan = fixture.service.prepareRunMessage(user(),
                command(userText, ChatRunMode.NEXT, null, null, null), fixture.session, runId, List.of());
        ChatMessage assistant = saveAssistant(fixture, assistantText, runId, plan.userMessage().id(), null);
        return new MessagePair(plan.userMessage(), assistant);
    }

    private ChatMessage saveAssistant(TestFixture fixture, String content, String runId,
                                      String parentMessageId, String regeneratedFromMessageId) {
        return fixture.service.saveAssistantMessage(new AssistantMessageSaveCommand(
                "tenant1", "user1", fixture.session, content, runId, parentMessageId,
                regeneratedFromMessageId, List.of(), null));
    }

    private ChatCommand command(String message, ChatRunMode mode, String parentMessageId,
                                String editedMessageId, String regeneratedMessageId) {
        return new ChatCommand("cmd", "tenant1", "user1", "session1", null, "web", message, List.of(), Map.of(),
                mode, parentMessageId, editedMessageId, regeneratedMessageId);
    }

    private TestFixture fixture() {
        return fixture(null, null);
    }

    private TestFixture fixture(String appId, String appName) {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        ChatSession session = sessions.save(new ChatSession("session1", "tenant1", "user1", "title", "ACTIVE", "web",
                appId, appName, null, "session1", null, null, 0L, null, Instant.now(), Instant.now()));
        return new TestFixture(service(sessions, messages), sessions, session);
    }

    private ChatSession taggedSession(String id, String appId, String appName, Instant updatedAt) {
        return new ChatSession(id, "tenant1", "user1", id, "ACTIVE", "web", appId, appName,
                null, id, null, null, 0L, null, updatedAt.minusSeconds(1), updatedAt);
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
        return service(sessions, messages, chatRunService, bindingService, null);
    }

    private SessionApplicationService service(InMemorySessionRepository sessions, InMemoryMessageRepository messages,
                                              ChatRunApplicationService chatRunService,
                                              RuntimeBindingApplicationService bindingService,
                                              ChatShareRepository shareRepository) {
        return service(sessions, messages, chatRunService, bindingService, shareRepository, null);
    }

    private SessionApplicationService service(InMemorySessionRepository sessions, InMemoryMessageRepository messages,
                                              ChatRunApplicationService chatRunService,
                                              RuntimeBindingApplicationService bindingService,
                                              ChatShareRepository shareRepository,
                                              ChatRunStopCoordinator stopCoordinator) {
        return new SessionApplicationService(
                sessions,
                messages,
                new IncrementingIdGenerator(),
                new PermissionChecker(),
                chatRunService,
                bindingService,
                shareRepository,
                stopCoordinator == null ? null : singletonProvider(stopCoordinator)
        );
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private ChatRunApplicationService activeRunService(String... activeSessionIds) {
        return new ActiveRunService(List.of(activeSessionIds));
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
                    session.status(), session.channel(), session.appId(), session.appName(),
                    session.currentLeafMessageId(), session.rootSessionId(),
                    session.branchSourceSessionId(), session.branchSourceMessageId(), next,
                    session.latestMessageSeq(), session.lastReadSeq(), session.metadataJson(),
                    session.createdAt(), Instant.now()));
            return next;
        }

        @Override
        public void updateCurrentLeaf(String tenantId, String userId, String sessionId, String leafMessageId) {
            ChatSession session = findByTenantIdAndUserIdAndId(tenantId, userId, sessionId).orElseThrow();
            sessions.put(sessionId, new ChatSession(session.id(), session.tenantId(), session.userId(), session.title(),
                    session.status(), session.channel(), session.appId(), session.appName(),
                    leafMessageId, session.rootSessionId(),
                    session.branchSourceSessionId(), session.branchSourceMessageId(), session.lastNodeOrder(),
                    session.latestMessageSeq(), session.lastReadSeq(), session.metadataJson(),
                    session.createdAt(), Instant.now()));
        }
    }

    private ObjectProvider<ChatRunStopCoordinator> singletonProvider(ChatRunStopCoordinator coordinator) {
        return new ObjectProvider<>() {
            @Override
            public ChatRunStopCoordinator getObject(Object... args) {
                return coordinator;
            }

            @Override
            public ChatRunStopCoordinator getIfAvailable() {
                return coordinator;
            }

            @Override
            public ChatRunStopCoordinator getIfUnique() {
                return coordinator;
            }

            @Override
            public ChatRunStopCoordinator getObject() {
                return coordinator;
            }
        };
    }

    private static class CountingStopCoordinator extends ChatRunStopCoordinator {
        private final List<String> stoppedSessions = new ArrayList<>();
        private final List<String> stoppedRuns = new ArrayList<>();

        CountingStopCoordinator() {
            super(null, null, null, null, null, null,
                    (ChatInteractionApplicationService) null, null);
        }

        @Override
        public void stopRunForSessionDelete(UserContext user, ChatRun run, ChatSession sessionSnapshot) {
            stoppedSessions.add(sessionSnapshot.id());
            stoppedRuns.add(run.id());
        }
    }

    private static class ActiveRunService extends ChatRunApplicationService {
        private final List<String> activeSessionIds;

        ActiveRunService(List<String> activeSessionIds) {
            super(null, null, null, new PermissionChecker(), null);
            this.activeSessionIds = activeSessionIds;
        }

        @Override
        public Optional<ChatRun> findActiveRun(UserContext user, String sessionId) {
            if (!activeSessionIds.contains(sessionId)) {
                return Optional.empty();
            }
            Instant now = Instant.now();
            return Optional.of(new ChatRun("run-" + sessionId, user.tenantId(), user.ownerUserId(), sessionId,
                    ChatRunStatus.RUNNING, "AGENT_RUNTIME", null, "relay", null, 1L,
                    null, null, now, null, Map.of(), now, now));
        }
    }

    private static class CountingRuntimeBindingService extends RuntimeBindingApplicationService {
        private int cancellations;

        CountingRuntimeBindingService() {
            super(null, null, null, Duration.ofDays(3), "relay");
        }

        @Override
        public void cancelAllForSession(String tenantId, String userId, String sessionId) {
            cancellations++;
        }
    }

    private static class RecordingShareRepository implements ChatShareRepository {
        private final List<String> revokedSessions = new ArrayList<>();

        @Override
        public ChatShare save(ChatShare share) {
            return share;
        }

        @Override
        public Optional<ChatShare> findById(String shareId) {
            return Optional.empty();
        }

        @Override
        public ChatSharePage pageByOwner(String tenantId, String ownerUserId, int curPage, int pageSize) {
            return new ChatSharePage(List.of(), 1, 20, 0, 0);
        }

        @Override
        public void revokeActiveBySession(String tenantId, String ownerUserId, String sessionId, Instant revokedAt) {
            revokedSessions.add(sessionId);
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
            return pageMessages(new ChatMessagePageQuery(tenantId, userId, sessionId, null, cursor, limit));
        }

        @Override
        public ChatMessagePage pageMessages(ChatMessagePageQuery query) {
            List<ChatMessage> items = messages.values().stream()
                    .filter(message -> query.tenantId().equals(message.tenantId()))
                    .filter(message -> query.userId().equals(message.userId()))
                    .filter(message -> query.sessionId().equals(message.sessionId()))
                    .sorted(Comparator.comparing(ChatMessage::nodeOrder, Comparator.nullsLast(Long::compareTo))
                            .thenComparing(ChatMessage::createdAt))
                    .limit(query.limit())
                    .map(this::withAttachments)
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
                    .map(this::withAttachments)
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
            return path.stream().map(this::withAttachments).toList();
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

        @Override
        public List<ChatMessageAttachment> findAttachmentsByMessageIds(String tenantId, String userId, String sessionId,
                                                                       List<String> messageIds) {
            return attachments.stream()
                    .filter(attachment -> tenantId.equals(attachment.tenantId()))
                    .filter(attachment -> userId.equals(attachment.userId()))
                    .filter(attachment -> sessionId.equals(attachment.sessionId()))
                    .filter(attachment -> messageIds.contains(attachment.messageId()))
                    .toList();
        }

        private ChatMessage withAttachments(ChatMessage message) {
            return message.withAttachments(findAttachments(message.tenantId(), message.userId(), message.id()));
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
