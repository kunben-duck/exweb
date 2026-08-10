package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

class ChatRunStopAssistantProjectorTest {
    private final SessionApplicationService sessionService = mock(SessionApplicationService.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final UserContext user = new UserContext("tenant1", "user1", "User One");

    @Test
    void reuseAssistantWithOnlyControlPartPreservesOriginalProjection() {
        ChatRun run = continuationRun();
        ChatSession session = session();
        when(sessionService.getSession(user, run.sessionId())).thenReturn(session);
        AssistantAssembly assistant = new AssistantAssembly();
        assistant.observe(RuntimeEvent.card(run.id(), run.sessionId(), Map.of(
                "source", "chatservice",
                "sourceType", "clarification-response",
                "interactionType", "AGENT_CLARIFICATION",
                "answerText", "方案A")));

        ChatRunStopAssistantProjector.Projection projection = projector().project(
                user, run, "USER_STOP", null, assistant);

        assertThat(projection.messageReady()).isTrue();
        assertThat(projection.assistantMessageId()).isEqualTo("msg-assistant");
        assertThat(projection.preserveExistingProjection()).isTrue();
        assertThat(projection.command().content()).isEmpty();
        assertThat(projection.command().metadataJson()).isNull();
        assertThat(projection.command().safePartDrafts())
                .extracting(part -> part.partType())
                .containsExactly("AGENT_CLARIFICATION_RESPONSE");
    }

    @Test
    void reuseAssistantWithoutNewOutputDoesNotOverwriteWaitingCard() {
        ChatRunStopAssistantProjector.Projection projection = projector().project(
                user, continuationRun(), "USER_STOP", session(), new AssistantAssembly());

        assertThat(projection.messageReady()).isFalse();
        assertThat(projection.command()).isNull();
    }

    @Test
    void reuseAssistantWithBusinessDeltaSavesCurrentPartialAnswer() {
        ChatRun run = continuationRun();
        AssistantAssembly assistant = new AssistantAssembly();
        assistant.observe(MessageDeltaEvent.of(run.id(), run.sessionId(), "partial answer"));

        ChatRunStopAssistantProjector.Projection projection = projector().project(
                user, run, "USER_STOP", session(), assistant);

        assertThat(projection.messageReady()).isTrue();
        assertThat(projection.preserveExistingProjection()).isFalse();
        assertThat(projection.command().content()).isEqualTo("partial answer");
        assertThat(projection.command().metadataJson()).contains("USER_STOP");
    }

    private ChatRunStopAssistantProjector projector() {
        return new ChatRunStopAssistantProjector(sessionService, idGenerator);
    }

    private ChatRun continuationRun() {
        Instant now = Instant.now();
        return new ChatRun(
                "run-b", "tenant1", "user1", "session1", ChatRunStatus.CANCELLING,
                "AGENT_RUNTIME", null, "relay", "relay-session",
                ChatRunMode.CONTINUE_INTERACTION, "msg-user", "msg-user", null,
                1L, 2L, "USER_STOP", now, null,
                Map.of(
                        "interactionId", "interaction-1",
                        "interactionAssistantMessageId", "msg-assistant",
                        "interactionMessageStrategy", "REUSE_ASSISTANT"),
                now, now);
    }

    private ChatSession session() {
        Instant now = Instant.now();
        return new ChatSession(
                "session1", "tenant1", "user1", "Title", "ACTIVE", "web", now, now);
    }
}
