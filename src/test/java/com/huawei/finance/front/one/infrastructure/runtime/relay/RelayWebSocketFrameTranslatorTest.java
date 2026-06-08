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
    void relayStreamCompleteTextIsTranslatedToMessageCompletedEvent() {
        List<ChatEvent> events = translator.translate("run1", "session1", "steam-complete");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.completed");
    }

    @Test
    void relayAgentFrameBecomesAssistantDeltaAndKeepsRuntimeSession() {
        List<ChatEvent> events = translator.translate("run1", "session1",
                "{\"type\":\"agent\",\"agent_name\":\"delegate_agent\",\"content\":\"你好\","
                        + "\"session_id\":\"relay-session-1\",\"timestamp\":123}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.delta");
        assertThat(events.getFirst().payload())
                .containsEntry("delta", "你好")
                .containsEntry("sourceType", "agent")
                .containsEntry("agentName", "delegate_agent")
                .containsEntry("runtimeSessionId", "relay-session-1");
    }

    @Test
    void relayAgentFinalSnapshotDoesNotBecomeDelta() {
        List<ChatEvent> events = translator.translate("run1", "session1",
                "{\"type\":\"agent\",\"agent_name\":\"delegate_agent\",\"is_streaming\":false,"
                        + "\"content\":\"最终回答\\n保留格式\",\"session_id\":\"relay-session-1\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.snapshot");
        assertThat(events.getFirst().payload())
                .containsEntry("content", "最终回答\n保留格式")
                .containsEntry("sourceType", "agent")
                .containsEntry("agentName", "delegate_agent")
                .containsEntry("runtimeSessionId", "relay-session-1");
    }

    @Test
    void relayAgentFinalSnapshotSupportsStreamingAliases() {
        List<ChatEvent> events = translator.translate("run1", "session1",
                "{\"type\":\"agent\",\"isStreaming\":\"false\",\"context\":\"  Markdown **正文**  \"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.snapshot");
        assertThat(events.getFirst().payload()).containsEntry("content", "  Markdown **正文**  ");
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
    void projectHomeFrameBecomesRuntimeMetadata() {
        List<ChatEvent> events = translator.translate("run1", "session1",
                "{\"type\":\"project_home\",\"project_home\":\"/tmp/xxx\",\"nullable\":null,"
                        + "\"authorization\":\"Bearer secret\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.metadata");
        assertThat(events.getFirst().payload())
                .containsEntry("source", "relay")
                .containsEntry("sourceType", "project_home")
                .containsEntry("metadataType", "project_home")
                .containsEntry("projectHome", "/tmp/xxx");
    }

    @Test
    void relayProgressFrameBecomesRuntimeProgress() {
        List<ChatEvent> events = translator.translate("run1", "session1",
                "{\"type\":\"relay-progress\",\"content\":\"处理中\",\"instansid\":\"relay-session-1\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.progress");
        assertThat(events.getFirst().payload())
                .containsEntry("sourceType", "relay-progress")
                .containsEntry("runtimeSessionId", "relay-session-1")
                .containsEntry("text", "处理中");
    }

    @Test
    void relayToolFrameBecomesRuntimeTool() {
        List<ChatEvent> events = translator.translate("run1", "session1",
                "{\"type\":\"tool_call_streaming\",\"agent_name\":\"delegate\",\"too_name\":\"eureka_chat\","
                        + "\"input_preview\":\"查询报销流程\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.tool");
        assertThat(events.getFirst().payload())
                .containsEntry("toolName", "eureka_chat")
                .containsEntry("inputPreview", "查询报销流程");
    }

    @Test
    void unknownJsonStillFallsBackToRuntimeEventWithRedaction() {
        List<ChatEvent> events = translator.translate("run1", "session1",
                "{\"type\":\"custom-event\",\"authorization\":\"Bearer secret\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.event");
        assertThat(events.getFirst().payload().get("sourcePayload"))
                .asString()
                .doesNotContain("Bearer secret")
                .contains("[REDACTED]");
    }

    @Test
    void errorFrameFailsProtocol() {
        assertThatThrownBy(() -> translator.translate("run1", "session1",
                "{\"type\":\"error\",\"message\":\"relay failed\"}"))
                .isInstanceOf(RelayRuntimeProtocolException.class)
                .hasMessage("relay failed");
    }
}
