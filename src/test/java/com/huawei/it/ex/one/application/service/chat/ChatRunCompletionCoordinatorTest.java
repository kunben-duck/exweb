package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.service.runtime.RuntimeStreamLimitExceededException;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.RunCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class ChatRunCompletionCoordinatorTest {
    private final ChatRunCompletionCoordinator coordinator = new ChatRunCompletionCoordinator(
            null, null, null, null, null, null, null);

    @Test
    void resourceLimitWithoutBusinessOutputDoesNotExposeReusableAssistant() {
        AssistantAssembly assistant = new AssistantAssembly();
        assistant.observe(interactionResponse());

        ChatRunCompletionCoordinator.CompletionPlan plan = coordinator.prepare(
                resourceLimitFailure(), context(assistant));

        assertThat(plan.target().messageReady()).isFalse();
        assertThat(plan.target().assistantMessageId()).isNull();
        assertThat(plan.eventToPersist().payload())
                .containsEntry("messageReady", false)
                .containsEntry("partialAnswerSaved", false)
                .doesNotContainKeys("assistantMessageId", "feedbackTargetMessageId");
    }

    @Test
    void resourceLimitWithBusinessOutputKeepsExistingPartialAssistantBehavior() {
        AssistantAssembly assistant = new AssistantAssembly();
        assistant.observe(interactionResponse());
        assistant.observe(MessageDeltaEvent.of("run-b", "session1", "部分回答"));

        ChatRunCompletionCoordinator.CompletionPlan plan = coordinator.prepare(
                resourceLimitFailure(), context(assistant));

        assertThat(plan.target().messageReady()).isTrue();
        assertThat(plan.target().assistantMessageId()).isEqualTo("msg-assistant");
        assertThat(plan.eventToPersist().payload())
                .containsEntry("messageReady", true)
                .containsEntry("partialAnswerSaved", true)
                .containsEntry("assistantMessageId", "msg-assistant")
                .containsEntry("feedbackTargetMessageId", "msg-assistant");
    }

    @Test
    void completedReusableContinuationStillTargetsExistingAssistantWithoutOutput() {
        ChatRunCompletionCoordinator.CompletionPlan plan = coordinator.prepare(
                RunCompletedEvent.of("run-b", "session1", Map.of()),
                context(new AssistantAssembly()));

        assertThat(plan.target().messageReady()).isTrue();
        assertThat(plan.target().assistantMessageId()).isEqualTo("msg-assistant");
    }

    private ChatEvent resourceLimitFailure() {
        return ErrorEvent.of(
                "run-b",
                "session1",
                RuntimeStreamLimitExceededException.CODE,
                "runtime stream limit exceeded",
                Map.of("code", RuntimeStreamLimitExceededException.CODE));
    }

    private RuntimeEvent interactionResponse() {
        return RuntimeEvent.card("run-b", "session1", Map.of(
                "source", "chatservice",
                "sourceType", "intent-clarification-response",
                "interactionType", "INTENT_CLARIFICATION",
                "clarificationType", "AMBIGUOUS_ROUTE",
                "answerText", "财经知识助手"
        ));
    }

    private RunEventPipelineContext context(AssistantAssembly assistant) {
        Instant now = Instant.now();
        ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "test", "ACTIVE", "web", now, now);
        ChatInteractionRequest interaction = new ChatInteractionRequest(
                "interaction1", "tenant1", "user1", session.id(), "run-a", "run-b",
                "msg-user", "msg-assistant", "intent-agent", null, null, null,
                ChatInteractionType.INTENT_CLARIFICATION, ChatInteractionStatus.RESPONDING,
                Map.of("clarificationType", "AMBIGUOUS_ROUTE"), Map.of(),
                now.plusSeconds(60), now, null, now, now);
        return new RunEventPipelineContext(
                new UserContext("tenant1", "user1", "User One"),
                session,
                null,
                new AtomicReference<>(),
                new AtomicReference<>(),
                assistant,
                "run-b",
                null,
                new AtomicReference<>(),
                interaction,
                null,
                List.of());
    }
}
