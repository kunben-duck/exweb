/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;
import com.huawei.it.ex.one.domain.chat.MessageCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunCancelledEvent;
import com.huawei.it.ex.one.domain.chat.RunCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunStartedEvent;
import com.huawei.it.ex.one.domain.chat.RunWaitingUserEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class RunDurationTest {
    private final Instant start = Instant.parse("2026-10-15T01:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void measuresWholeRunWithoutSummingAgentCallsOrPreviousRuns() {
        assertThat(RunDuration.between(start, start.plusMillis(12580)).millis()).isEqualTo(12580L);
        assertThat(RunDuration.between(start.plusSeconds(100), start.plusSeconds(104)).millis()).isEqualTo(4000L);
        assertThat(RunDuration.between(start, start.minusSeconds(1)).millis()).isZero();
        assertThat(RunDuration.between(start, start).millis()).isZero();
        assertThat(RunDuration.between(null, start).millis()).isNull();
        assertThat(RunDuration.between(start, null).millis()).isNull();
        assertThat(RunDuration.between(Instant.MIN, Instant.MAX).millis()).isNull();
    }

    @Test
    void finishedRunFreezesDurationButAsyncRunningIncludesCallbackWait() {
        ChatRun waiting = run(ChatRunStatus.WAITING_USER, start.plusMillis(12580));
        assertThat(RunDuration.forRun(waiting, start.plusSeconds(3600)).millis()).isEqualTo(12580L);
        assertThat(RunDuration.forRun(waiting.cancelling("stop"), start.plusSeconds(3600)).millis())
                .isEqualTo(12580L);
        assertThat(RunDuration.forRun(run(ChatRunStatus.RUNNING, null), start.plusSeconds(1800)).millis())
                .isEqualTo(1800000L);
        assertThat(RunDuration.forRun(null, start).millis()).isNull();
    }

    @Test
    void decoratesOnlyTerminalEventsAndPreservesTheirProtocol() throws Exception {
        RunDuration duration = RunDuration.between(start, start.plusMillis(12580));
        for (ChatEvent event : List.of(RunCompletedEvent.of("run1", "session1"),
                RunWaitingUserEvent.of("run1", "session1", Map.of("interactionId", "interaction1")),
                RunCancelledEvent.of("run1", "session1", "USER_STOP"),
                ErrorEvent.of("run1", "session1", "ERROR", "failed"))) {
            ChatEvent result = duration.decorate(event);
            assertThat(result.getClass()).isEqualTo(event.getClass());
            assertThat(result.runId()).isEqualTo(event.runId());
            assertThat(result.sessionId()).isEqualTo(event.sessionId());
            assertThat(result.sequence()).isEqualTo(event.sequence());
            assertThat(result.createdAt()).isEqualTo(event.createdAt());
            assertThat(result.payload()).containsAllEntriesOf(event.payload()).containsEntry("runDurationMs", 12580L);
            assertThat(mapper.readTree(mapper.writeValueAsString(result.payload())).get("runDurationMs").longValue())
                    .isEqualTo(12580L);
            assertThat(new RunDuration(null).decorate(event)).isSameAs(event);
        }
        for (ChatEvent event : List.of(RunStartedEvent.of("run1", "session1"),
                MessageCompletedEvent.of("run1", "session1"),
                RuntimeEvent.thinking("run1", "session1", Map.of("text", "thinking")))) {
            assertThat(duration.decorate(event)).isSameAs(event);
        }
    }

    @Test
    void replacesOnlyDurationWithoutDamagingMetadataAndOmitsUnknownValue() throws Exception {
        String original = "{\"skillId\":\"skill1\",\"asyncTask\":{\"status\":\"COMPLETED\"},"
                + "\"runDurationMs\":999999,\"extension\":[1,\"中文\",true]}";
        String result = new RunDuration(12580L).mergeMetadata(mapper, original);
        var expected = mapper.readTree(original);
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected).put("runDurationMs", 12580);
        assertThat(mapper.readTree(result)).isEqualTo(expected);
        assertThat(mapper.readTree(new RunDuration(null).mergeMetadata(mapper, result)).has("runDurationMs"))
                .isFalse();
        assertThat(new RunDuration(null).mergeMetadata(mapper, null)).isNull();
        assertThat(mapper.readTree(new RunDuration(1L).mergeMetadata(mapper, null)).get("runDurationMs").longValue())
                .isEqualTo(1L);
        for (String invalid : List.of("broken", "[]", "null", "3")) {
            assertThat(new RunDuration(1L).mergeMetadata(mapper, invalid)).isEqualTo(invalid);
        }
    }

    private ChatRun run(ChatRunStatus status, Instant finishedAt) {
        return new ChatRun("run1", "tenant1", "user1", "session1", status, "DOMAIN_AGENT", "skill1",
                "domain-agent", "agent-session", 1L, 2L, null, start, finishedAt, Map.of(), start, start);
    }
}
