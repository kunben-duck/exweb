package com.huawei.finance.front.one.infrastructure.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacySkillResponseNormalizerTest {
    private final LegacySkillResponseNormalizer normalizer = new LegacySkillResponseNormalizer(new ObjectMapper());

    @Test
    void mapsLegacyEventStreamFramesToChatServiceEvents() {
        String chunk = """
                message: {"traceId":"trace-1"}

                message: {"sessionId":"legacy-session-1"}

                message: {"cardUrl":"https://card","intent":"tax","skillId":"skill-tax"}

                message: {"processResult":{"dynamicResponse":[{"title":"工具调用","type":"str"}]}}

                message: {"content":"你好"}

                message: {"endFlag":true}

                """;

        List<ChatEvent> events = normalizer.normalize("run1", "session1", chunk);

        assertThat(events).extracting(ChatEvent::type).containsExactly(
                "runtime.metadata",
                "runtime.metadata",
                "runtime.metadata",
                "runtime.progress",
                "message.delta",
                "message.completed"
        );
        assertThat(events.get(0).payload()).containsEntry("metadataType", "trace")
                .containsEntry("traceId", "trace-1");
        assertThat(events.get(1).payload()).containsEntry("metadataType", "legacy_session")
                .containsEntry("legacySessionId", "legacy-session-1");
        assertThat(events.get(4).payload()).containsEntry("delta", "你好");
        assertThat(events.get(5).payload()).containsEntry("sourceType", "legacy-agent-end");
    }

    @Test
    void unknownJsonFallsBackToRuntimeEvent() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", "message: {\"foo\":\"bar\"}");

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("runtime.event");
        assertThat(events.get(0).payload()).containsEntry("source", "legacy-agent");
    }

    @Test
    void supportsLegacyMessagePrefixesAndRedactsSensitiveUnknownPayload() {
        String chunk = """
                message. {"content":"A"}

                message {"token":"secret","foo":"bar"}

                """;

        List<ChatEvent> events = normalizer.normalize("run1", "session1", chunk);

        assertThat(events).extracting(ChatEvent::type).containsExactly("message.delta", "runtime.event");
        assertThat(events.get(0).payload()).containsEntry("delta", "A");
        assertThat(events.get(1).payload().toString()).contains("[REDACTED]").doesNotContain("secret");
    }
}
