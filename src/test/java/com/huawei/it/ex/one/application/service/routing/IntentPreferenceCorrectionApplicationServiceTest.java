package com.huawei.it.ex.one.application.service.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.conversation.ChatInteractionRequestRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.intent.IntentPreferenceCorrectionRepository;
import com.huawei.it.ex.one.application.integration.intent.IntentPreferenceUnavailableException;
import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionRecordRepository;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.intent.IntentPreferenceCorrection;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class IntentPreferenceCorrectionApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-27T02:00:00Z");
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final ChatInteractionRequestRepository interactions = mock(ChatInteractionRequestRepository.class);
    private final IntentRecognitionRecordRepository recognitions = mock(IntentRecognitionRecordRepository.class);
    private final IntentPreferenceCorrectionRepository corrections = mock(IntentPreferenceCorrectionRepository.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final UserContext user = new UserContext("tenant", "user", "User");
    private final IntentPreferenceCorrectionApplicationService service =
            new IntentPreferenceCorrectionApplicationService(
                    messages, interactions, recognitions, corrections,
                    requested -> requested == null || requested.isBlank() ? "default-entry" : requested.trim(),
                    ids, Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void recordsCandidateSelectionFromTrustedMessageAndRecognition() {
        when(ids.newId(any(), any())).thenReturn("intent_pref_1");
        when(messages.findByOwnerAndId("tenant", "user", "msg-source"))
                .thenReturn(Optional.of(userMessage("msg-source", "run-source", "原始问题")));
        when(recognitions.findLatestRecognizedIntentName(
                "tenant", "user", "session", "run-source"))
                .thenReturn(Optional.of("原始意图"));

        service.record(user, new IntentPreferenceCorrectionCommand(
                "INTENT_CANDIDATE", " msg-source ",
                new IntentPreferenceCorrectionCommand.SelectedIntent("intent-new", "偏好意图"),
                null, " entry-a ")).block();

        ArgumentCaptor<IntentPreferenceCorrection> captor =
                ArgumentCaptor.forClass(IntentPreferenceCorrection.class);
        verify(corrections).upsert(captor.capture());
        assertThat(captor.getValue()).satisfies(saved -> {
            assertThat(saved.intentAccessName()).isEqualTo("entry-a");
            assertThat(saved.sessionId()).isEqualTo("session");
            assertThat(saved.sourceMessageId()).isEqualTo("msg-source");
            assertThat(saved.sourceType()).isEqualTo("INTENT_CANDIDATE");
            assertThat(saved.queryText()).isEqualTo("原始问题");
            assertThat(saved.preferenceIntent()).isEqualTo("偏好意图");
            assertThat(saved.originalIntent()).isEqualTo("原始意图");
            assertThat(saved.updatedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void recordsAnAnsweredManualAmbiguousSelection() {
        when(ids.newId(any(), any())).thenReturn("intent_pref_2");
        when(interactions.findByOwnerAndId("tenant", "user", "interaction-1"))
                .thenReturn(Optional.of(ambiguousInteraction(
                        ChatInteractionStatus.ANSWERED, "SELECT_CANDIDATE", "USER")));
        when(messages.findByOwnerAndId("tenant", "user", "msg-source"))
                .thenReturn(Optional.of(userMessage("msg-source", "run-source", "回退问题")));

        service.record(user, new IntentPreferenceCorrectionCommand(
                "AMBIGUOUS_ROUTE", null, null, "interaction-1", null)).block();

        ArgumentCaptor<IntentPreferenceCorrection> captor =
                ArgumentCaptor.forClass(IntentPreferenceCorrection.class);
        verify(corrections).upsert(captor.capture());
        assertThat(captor.getValue().queryText()).isEqualTo("可信原始问题");
        assertThat(captor.getValue().preferenceIntent()).isEqualTo("人工选择意图");
        assertThat(captor.getValue().originalIntent()).isNull();
        assertThat(captor.getValue().intentAccessName()).isEqualTo("default-entry");
    }

    @Test
    void recordsARespondingManualSelectionAfterTheContinuationRunWasAccepted() {
        when(ids.newId(any(), any())).thenReturn("intent_pref_responding");
        when(interactions.findByOwnerAndId("tenant", "user", "interaction-1"))
                .thenReturn(Optional.of(ambiguousInteraction(
                        ChatInteractionStatus.RESPONDING, "SELECT_CANDIDATE", "USER")));
        when(messages.findByOwnerAndId("tenant", "user", "msg-source"))
                .thenReturn(Optional.of(userMessage("msg-source", "run-source", "回退问题")));

        service.record(user, new IntentPreferenceCorrectionCommand(
                "AMBIGUOUS_ROUTE", null, null, "interaction-1", null)).block();

        verify(corrections).upsert(any());
    }

    @Test
    void rejectsAutoSelectedAmbiguousInteractionWithoutWriting() {
        when(interactions.findByOwnerAndId("tenant", "user", "interaction-1"))
                .thenReturn(Optional.of(ambiguousInteraction(
                        ChatInteractionStatus.ANSWERED, "AUTO_SELECT", "DELEGATED")));

        assertThatThrownBy(() -> service.record(user, new IntentPreferenceCorrectionCommand(
                        "AMBIGUOUS_ROUTE", null, null, "interaction-1", null)).block())
                .isInstanceOf(IllegalArgumentException.class);
        verify(corrections, never()).upsert(any());
    }

    @Test
    void mapsPreferencePersistenceFailureWithoutChangingValidationErrors() {
        when(ids.newId(any(), any())).thenReturn("intent_pref_3");
        when(messages.findByOwnerAndId("tenant", "user", "msg-source"))
                .thenReturn(Optional.of(userMessage("msg-source", "run-source", "原始问题")));
        org.mockito.Mockito.doThrow(new IllegalStateException("database down"))
                .when(corrections).upsert(any());

        assertThatThrownBy(() -> service.record(user, new IntentPreferenceCorrectionCommand(
                        "INTENT_CANDIDATE", "msg-source",
                        new IntentPreferenceCorrectionCommand.SelectedIntent("intent-new", "偏好意图"),
                        null, null)).block())
                .isInstanceOf(IntentPreferenceUnavailableException.class)
                .hasMessage("意图偏好记录暂不可用，请稍后重试");

        assertThatThrownBy(() -> service.record(user, new IntentPreferenceCorrectionCommand(
                        "UNKNOWN", null, null, null, null)).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selectionType仅支持");
    }

    private ChatMessage userMessage(String id, String runId, String content) {
        return new ChatMessage(
                id, "tenant", "user", "session", null, 1L, 0, 0,
                "user", content, null, runId, "NORMAL", false,
                null, null, null, null, null, List.of(), List.of(), NOW);
    }

    private ChatInteractionRequest ambiguousInteraction(
            ChatInteractionStatus status, String action, String selectionSource) {
        return new ChatInteractionRequest(
                "interaction-1", "tenant", "user", "session", "run-source", "run-next",
                "msg-source", "msg-assistant", "intent-agent", null, null, null,
                ChatInteractionType.INTENT_CLARIFICATION, status,
                Map.of("clarificationType", "AMBIGUOUS_ROUTE", "originalQuery", "可信原始问题"),
                Map.of("interactionAction", action, "selectionSource", selectionSource,
                        "selectedIntentName", "人工选择意图"),
                NOW.plusSeconds(3600), NOW, null, NOW.minusSeconds(60), NOW);
    }
}
