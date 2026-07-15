package com.huawei.it.ex.one.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatPayloadMapsTest {

    @Test
    void immutableCopyPreservesJsonNullAndDetachesFromSource() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("nestedNullable", null);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("nullable", null);
        source.put("nested", nested);

        Map<String, Object> copied = ChatPayloadMaps.immutableCopy(source);
        source.put("lateValue", "not-copied");

        assertThat(copied).containsKey("nullable").doesNotContainKey("lateValue");
        assertThat(copied.get("nullable")).isNull();
        assertThat(((Map<?, ?>) copied.get("nested")).get("nestedNullable")).isNull();
        assertThatThrownBy(() -> copied.put("newValue", "rejected"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void immutableCopyContinuesToRejectNullJsonObjectKeys() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put(null, "invalid");

        assertThatThrownBy(() -> ChatPayloadMaps.immutableCopy(source))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Chat payload key cannot be null");
    }

    @Test
    void eventPartInteractionAndSharePayloadsPreserveJsonNull() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceType", "nullable-event");
        payload.put("optionalValue", null);
        Instant now = Instant.now();

        RuntimeEvent runtimeEvent = new RuntimeEvent("run1", "session1", 0, now, "runtime.event", payload);
        MessageDeltaEvent deltaEvent = new MessageDeltaEvent("run1", "session1", 0, now, "delta", payload);
        MessageSnapshotEvent snapshotEvent = new MessageSnapshotEvent(
                "run1", "session1", 0, now, "snapshot", payload);
        StoredChatEvent storedEvent = new StoredChatEvent(
                "run1", "session1", 1, "runtime.event", now, payload);
        ChatMessagePartDraft draft = new ChatMessagePartDraft(
                "RUNTIME_EVENT", "nullable-event", null, payload);
        ChatMessagePart part = new ChatMessagePart(
                "part1", "tenant1", "user1", "session1", "message1", "run1",
                "RUNTIME_EVENT", "nullable-event", null, payload, 1, now);
        ChatShareSnapshotPart sharePart = new ChatShareSnapshotPart(
                "part1", "message1", "run1", "RUNTIME_EVENT", "nullable-event", null,
                null, null, null, null, null, payload, 1, now);
        ChatInteractionRequest interaction = new ChatInteractionRequest(
                "interaction1", "tenant1", "user1", "session1", "run1", null,
                "userMessage1", "assistantMessage1", "relay", "binding1", "runtimeSession1", null,
                ChatInteractionType.AGENT_CLARIFICATION, ChatInteractionStatus.WAITING,
                payload, payload, now.plusSeconds(60), null, null, now, now);

        assertJsonNull(runtimeEvent.payload());
        assertJsonNull(deltaEvent.payload());
        assertJsonNull(snapshotEvent.payload());
        assertJsonNull(storedEvent.payload());
        assertJsonNull(draft.payload());
        assertJsonNull(part.payload());
        assertJsonNull(sharePart.payload());
        assertJsonNull(interaction.requestPayload());
        assertJsonNull(interaction.responsePayload());
    }

    private void assertJsonNull(Map<String, Object> payload) {
        assertThat(payload).containsKey("optionalValue");
        assertThat(payload.get("optionalValue")).isNull();
    }
}
