/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventStore;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.CandidateDomainAgentSwitchCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class CandidateSwitchRouteTraceServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private static final UserContext USER = new UserContext("tenant1", "user1", "User One");

    private final ChatEventStore eventStore = mock(ChatEventStore.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CandidateSwitchRouteTraceService service =
            new CandidateSwitchRouteTraceService(eventStore, objectMapper);

    @Test
    void replaysOnlyIntentAndSkillSelectionFactsBeforeCurrentSwitchMarker() {
        List<ChatEvent> events = List.of(
                event(1L, "run.started", Map.of("status", "RUNNING")),
                event(2L, "runtime.progress", Map.of(
                        "source", "intent-agent",
                        "sourceType", "intent-start",
                        "message", "正在识别问题意图")),
                event(3L, "message.delta", Map.of("delta", "不应回放的正文")),
                event(4L, "runtime.progress", Map.of(
                        "source", "intent-agent",
                        "sourceType", "intent-result",
                        "intentName", "原技能A",
                        "runtimeBindingId", "binding-a")),
                event(5L, "runtime.metadata", Map.of(
                        "source", "chatservice",
                        "sourceType", "selectedDomainAgent",
                        "targetId", "skill-a",
                        "runtimeSessionId", "runtime-a")),
                event(6L, "runtime.metadata", Map.of(
                        "source", "intent-agent",
                        "sourceType", "intent-start",
                        CandidateSwitchRouteTrace.REPLAY_METADATA_KEY, Map.of(
                                "originRunId", "run_a",
                                "originSequence", 2L))),
                event(7L, "runtime.progress", Map.of(
                        "source", "chatservice",
                        "sourceType", "candidate-skill-switch",
                        "skillId", "skill-a")),
                event(8L, "runtime.card", Map.of(
                        "source", "domain-agent",
                        "sourceType", "openCard")),
                event(9L, "runtime.metadata", Map.of(
                        "source", "domain-agent",
                        "sourceType", "agent.refusal")),
                event(10L, "run.completed", Map.of("status", "COMPLETED")));
        when(eventStore.findFirstByOwnerAndRunAfterSeq(
                "tenant1", "user1", "session1", "run_a",
                new ChatEventStore.RunEventWindow(
                        0L, CandidateSwitchRouteTraceService.SOURCE_SCAN_LIMIT)))
                .thenReturn(events);

        CandidateSwitchRouteTrace trace = service.load(USER, run(), command());
        List<ChatEvent> replay = trace.eventsFor("run_b", "session1");

        assertThat(replay)
                .extracting(event -> event.payload().get("sourceType"))
                .containsExactly(
                        "intent-start",
                        "intent-result",
                        "selectedDomainAgent",
                        "candidate-skill-switch",
                        "candidate-skill-switch");
        assertThat(replay).allSatisfy(event -> {
            assertThat(event.runId()).isEqualTo("run_b");
            assertThat(event.sessionId()).isEqualTo("session1");
            assertThat(event.sequence()).isZero();
        });
        assertThat(replay.get(1).payload()).doesNotContainKeys("runtimeBindingId", "runtimeSessionId");
        assertThat(replay.get(2).payload()).doesNotContainKeys("runtimeBindingId", "runtimeSessionId");
        assertThat(replay.getFirst().payload().get(CandidateSwitchRouteTrace.REPLAY_METADATA_KEY))
                .isEqualTo(Map.of("originRunId", "run_a", "originSequence", 2L));
        assertThat(replay.getLast().payload())
                .doesNotContainKey(CandidateSwitchRouteTrace.REPLAY_METADATA_KEY)
                .containsEntry("sourceRunId", "run_a")
                .containsEntry("targetId", "skill_b")
                .containsEntry("skillId", "skill_b")
                .containsEntry("intentId", "intent_b")
                .containsEntry("intentName", "候选技能B");
        verify(eventStore).findFirstByOwnerAndRunAfterSeq(
                "tenant1", "user1", "session1", "run_a",
                new ChatEventStore.RunEventWindow(
                        0L, CandidateSwitchRouteTraceService.SOURCE_SCAN_LIMIT));
    }

    @Test
    void keepsLatestInheritedFactsWithinCountAndByteLimits() throws Exception {
        List<ChatEvent> events = new ArrayList<>();
        for (long sequence = 1L; sequence <= 40L; sequence++) {
            events.add(event(sequence, "runtime.progress", Map.of(
                    "source", "intent-agent",
                    "sourceType", "intent-progress",
                    "message", "step-" + sequence)));
        }
        when(eventStore.findFirstByOwnerAndRunAfterSeq(
                "tenant1", "user1", "session1", "run_a",
                new ChatEventStore.RunEventWindow(
                        0L, CandidateSwitchRouteTraceService.SOURCE_SCAN_LIMIT)))
                .thenReturn(events);

        List<ChatEvent> replay = service.load(USER, run(), command()).eventsFor("run_b", "session1");

        assertThat(replay).hasSize(CandidateSwitchRouteTraceService.MAX_TRACE_EVENTS);
        List<Long> originSequences = replay.subList(0, replay.size() - 1).stream()
                .map(event -> (Map<?, ?>) event.payload()
                        .get(CandidateSwitchRouteTrace.REPLAY_METADATA_KEY))
                .map(replayMetadata -> ((Number) replayMetadata.get("originSequence")).longValue())
                .toList();
        assertThat(originSequences)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(10L, 40L)
                        .boxed()
                        .toList());
        long serializedBytes = 0L;
        for (ChatEvent event : replay) {
            serializedBytes += objectMapper.writeValueAsBytes(Map.of(
                    "type", event.type(),
                    "payload", event.payload())).length;
        }
        assertThat(serializedBytes).isLessThanOrEqualTo(CandidateSwitchRouteTraceService.MAX_TRACE_BYTES);
    }

    @Test
    void skipsOversizedInheritedFactButStillEmitsSwitchMarker() {
        when(eventStore.findFirstByOwnerAndRunAfterSeq(
                "tenant1", "user1", "session1", "run_a",
                new ChatEventStore.RunEventWindow(
                        0L, CandidateSwitchRouteTraceService.SOURCE_SCAN_LIMIT)))
                .thenReturn(List.of(
                        event(1L, "runtime.thinking", Map.of(
                                "source", "intent-agent",
                                "sourceType", "intent-delta",
                                "text", "x".repeat((int) CandidateSwitchRouteTraceService.MAX_TRACE_BYTES))),
                        event(2L, "runtime.metadata", Map.of(
                                "source", "chatservice",
                                "sourceType", "selectedDomainAgent",
                                "targetId", "skill-a"))));

        List<ChatEvent> replay = service.load(USER, run(), command()).eventsFor("run_b", "session1");

        assertThat(replay)
                .extracting(event -> event.payload().get("sourceType"))
                .containsExactly("selectedDomainAgent", "candidate-skill-switch");
    }

    @Test
    void continuesWithSwitchMarkerWhenSourceTraceReadFails() {
        when(eventStore.findFirstByOwnerAndRunAfterSeq(
                "tenant1", "user1", "session1", "run_a",
                new ChatEventStore.RunEventWindow(
                        0L, CandidateSwitchRouteTraceService.SOURCE_SCAN_LIMIT)))
                .thenThrow(new IllegalStateException("database unavailable"));

        List<ChatEvent> replay = service.load(USER, run(), command()).eventsFor("run_b", "session1");

        assertThat(replay).singleElement().satisfies(event -> assertThat(event.payload())
                .containsEntry("sourceType", "candidate-skill-switch")
                .containsEntry("skillId", "skill_b"));
    }

    private ChatEvent event(long sequence, String type, Map<String, Object> payload) {
        return new StoredChatEvent("run_a", "session1", sequence, type, NOW, payload);
    }

    private ChatRun run() {
        return new ChatRun(
                "run_a", "tenant1", "user1", "session1", ChatRunStatus.COMPLETED,
                "DOMAIN_AGENT", "skill_a", "domain-agent", null,
                ChatRunMode.NEXT, null, "msg_user", "msg_assistant_a",
                1L, 10L, null, NOW, NOW,
                Map.of(), NOW, NOW);
    }

    private CandidateDomainAgentSwitchCommand command() {
        return new CandidateDomainAgentSwitchCommand(
                "run_a",
                "msg_user",
                "skill_b",
                SelectedIntentContext.attach(Map.of(), "intent_b", "候选技能B"),
                null,
                "finance_pc_entry");
    }
}
