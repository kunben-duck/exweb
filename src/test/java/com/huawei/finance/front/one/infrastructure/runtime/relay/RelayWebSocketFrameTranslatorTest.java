package com.huawei.finance.front.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelayWebSocketFrameTranslatorTest {
    private final RelayWebSocketFrameTranslator translator = new RelayWebSocketFrameTranslator(
            new RelayRuntimeResponseNormalizer(new ObjectMapper()));

    @Test
    void plainTextFrameIsTranslatedToDeltaEvent() {
        List<ChatEvent> events = translator.translate("run1", "session1", "hello");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.delta");
        assertThat(events.getFirst().payload()).containsEntry("delta", "hello");
    }

    @Test
    void jsonDeltaFrameKeepsRuntimeSessionId() {
        List<ChatEvent> events = translator.translate("run1", "session1",
                "{\"type\":\"message.delta\",\"delta\":\"hi\",\"runtimeSessionId\":\"runtime-1\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.delta");
        assertThat(events.getFirst().payload())
                .containsEntry("delta", "hi")
                .containsEntry("runtimeSessionId", "runtime-1");
    }

    @Test
    void completedFrameIsTranslatedToMessageCompletedEvent() {
        List<ChatEvent> events = translator.translate("run1", "session1",
                "{\"type\":\"message.completed\",\"runtimeSessionId\":\"runtime-1\",\"raw\":\"ignored\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.completed");
        assertThat(events.getFirst().payload())
                .containsEntry("status", "MESSAGE_COMPLETED")
                .containsEntry("runtimeSessionId", "runtime-1")
                .doesNotContainKey("raw");
    }

    @Test
    void sseDoneFrameIsTranslatedToMessageCompletedEvent() {
        List<ChatEvent> events = translator.translate("run1", "session1", "data: [DONE]\n\n");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.completed");
    }

    @Test
    void multipleSseDataFramesAreTranslatedIndependently() {
        List<ChatEvent> events = translator.translate("run1", "session1",
                "data: {\"delta\":\"你\"}\n\n"
                        + "data: {\"delta\":\"好\"}\n\n"
                        + "data: [DONE]\n\n");

        assertThat(events).hasSize(3);
        assertThat(events.get(0).payload()).containsEntry("delta", "你");
        assertThat(events.get(1).payload()).containsEntry("delta", "好");
        assertThat(events.get(2).type()).isEqualTo("message.completed");
    }

    @Test
    void openAiLikeChunkIsTranslatedToDeltaEvent() {
        List<ChatEvent> events = translator.translate("run1", "session1",
                "{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().payload()).containsEntry("delta", "hello");
    }

    @Test
    void openAiLikeMetadataOnlyDeltaFrameIsIgnored() {
        List<ChatEvent> events = translator.translate("run1", "session1",
                "{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}");

        assertThat(events).isEmpty();
    }

    @Test
    void unknownJsonFrameBecomesRuntimeEventWithoutLeakingSensitivePayload() {
        List<ChatEvent> events = translator.translate("run1", "session1",
                "{\"type\":\"project_home\",\"project_home\":\"/tmp/xxx\",\"nullable\":null,"
                        + "\"authorization\":\"Bearer secret\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.event");
        assertThat(events.getFirst().payload())
                .containsEntry("source", "relay")
                .containsEntry("sourceType", "project_home")
                .containsEntry("channel", "runtime");
        assertThat(events.getFirst().payload().get("sourcePayload"))
                .asString()
                .contains("project_home")
                .doesNotContain("Bearer secret")
                .contains("[REDACTED]");
    }

    @Test
    void progressMessageFrameDoesNotBecomeAssistantDelta() {
        List<ChatEvent> events = translator.translate("run1", "session1",
                "{\"type\":\"progress\",\"message\":\"处理中\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.event");
        assertThat(events.getFirst().payload())
                .containsEntry("sourceType", "progress")
                .containsEntry("channel", "progress")
                .containsEntry("text", "处理中");
    }

    @Test
    void errorFrameFailsProtocol() {
        assertThatThrownBy(() -> translator.translate("run1", "session1",
                "{\"type\":\"error\",\"message\":\"relay failed\"}"))
                .isInstanceOf(RelayRuntimeProtocolException.class)
                .hasMessage("relay failed");
    }
}
