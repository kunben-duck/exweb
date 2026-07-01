package com.huawei.finance.front.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelayRuntimeResponseNormalizerTest {
    private final RelayRuntimeResponseNormalizer normalizer = new RelayRuntimeResponseNormalizer(new ObjectMapper());

    @Test
    void plainTextChunkIsTranslatedToDeltaEvent() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", "hello");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.delta");
        assertThat(events.getFirst().payload()).containsEntry("delta", "hello");
    }

    @Test
    void jsonDeltaChunkKeepsRuntimeSessionId() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"message.delta\",\"delta\":\"hi\",\"runtimeSessionId\":\"runtime-1\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.delta");
        assertThat(events.getFirst().payload())
                .containsEntry("delta", "hi")
                .containsEntry("runtimeSessionId", "runtime-1");
    }

    @Test
    void completedChunkIsTranslatedToMessageCompletedEvent() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
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
        List<ChatEvent> events = normalizer.normalize("run1", "session1", "steam-complete");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.completed");
    }

    @Test
    void relayAgentStreamingChunkBecomesAssistantDeltaAndKeepsRuntimeSession() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
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
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
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
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"agent\",\"isStreaming\":\"false\",\"context\":\"  Markdown **正文**  \"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.snapshot");
        assertThat(events.getFirst().payload()).containsEntry("content", "  Markdown **正文**  ");
    }

    @Test
    void sseDoneChunkIsTranslatedToMessageCompletedEvent() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", "data: [DONE]\n\n");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.completed");
    }

    @Test
    void multipleSseDataFramesAreTranslatedIndependently() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
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
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().payload()).containsEntry("delta", "hello");
    }

    @Test
    void openAiLikeMetadataOnlyDeltaFrameIsIgnored() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}");

        assertThat(events).isEmpty();
    }

    @Test
    void relayProgressAndToolFramesBecomeRuntimeEvents() {
        List<ChatEvent> progressEvents = normalizer.normalize("run1", "session1",
                "{\"type\":\"relay-progress\",\"content\":\"处理中\",\"instansid\":\"relay-session-1\"}");
        List<ChatEvent> toolEvents = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool_call_streaming\",\"agent_name\":\"delegate\",\"too_name\":\"eureka_chat\","
                        + "\"input_preview\":\"查询报销流程\"}");

        assertThat(progressEvents).hasSize(1);
        assertThat(progressEvents.getFirst().type()).isEqualTo("runtime.progress");
        assertThat(progressEvents.getFirst().payload())
                .containsEntry("sourceType", "relay-progress")
                .containsEntry("runtimeSessionId", "relay-session-1")
                .containsEntry("text", "处理中");
        assertThat(toolEvents).hasSize(1);
        assertThat(toolEvents.getFirst().type()).isEqualTo("runtime.tool");
        assertThat(toolEvents.getFirst().payload())
                .containsEntry("toolName", "eureka_chat")
                .containsEntry("inputPreview", "查询报销流程");
    }

    @Test
    void relayWebSocketThinkingAndToolExecutionFramesBecomeRuntimeEvents() {
        List<ChatEvent> reasoningEvents = normalizer.normalize("run1", "session1",
                "{\"type\":\"agent-reasoning\",\"agent_name\":\"PlanAgent\",\"thought\":\"分析问题\","
                        + "\"is_start\":true}");
        List<ChatEvent> thinkingContentEvents = normalizer.normalize("run1", "session1",
                "{\"type\":\"thinking-content-update\",\"agent_name\":\"delegate\","
                        + "\"operation_id\":\"op-1\",\"content\":\"思考片段\"}");
        List<ChatEvent> toolExecutionEvents = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-execution\",\"agent_name\":\"delegate\",\"tool_name\":\"mcp__x__search\","
                        + "\"tool_id\":\"tool-1\",\"is_start\":false,\"result_summary\":\"完成\"}");

        assertThat(reasoningEvents).hasSize(1);
        assertThat(reasoningEvents.getFirst().type()).isEqualTo("runtime.thinking");
        assertThat(reasoningEvents.getFirst().payload())
                .containsEntry("sourceType", "agent-reasoning")
                .containsEntry("status", "STARTED")
                .containsEntry("text", "分析问题");
        assertThat(thinkingContentEvents).hasSize(1);
        assertThat(thinkingContentEvents.getFirst().type()).isEqualTo("runtime.thinking");
        assertThat(thinkingContentEvents.getFirst().payload())
                .containsEntry("sourceType", "thinking-content-update")
                .containsEntry("status", "STREAMING")
                .containsEntry("text", "思考片段")
                .containsEntry("operationId", "op-1");
        assertThat(toolExecutionEvents).hasSize(1);
        assertThat(toolExecutionEvents.getFirst().type()).isEqualTo("runtime.tool");
        assertThat(toolExecutionEvents.getFirst().payload())
                .containsEntry("sourceType", "tool-execution")
                .containsEntry("status", "ENDED")
                .containsEntry("toolName", "mcp__x__search")
                .containsEntry("resultSummary", "完成");
    }

    @Test
    void relayWebSocketSessionStateBecomesRuntimeMetadata() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"session-state\",\"state\":\"idle\",\"detail\":\"Waiting\","
                        + "\"session_id\":\"relay-session-1\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.metadata");
        assertThat(events.getFirst().payload())
                .containsEntry("sourceType", "session-state")
                .containsEntry("metadataType", "session_state")
                .containsEntry("state", "idle")
                .containsEntry("runtimeSessionId", "relay-session-1");
    }

    @Test
    void relayReferenceFramesBecomeRuntimeReferenceEvents() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"url_moderation\",\"url_moderation_result\":{"
                        + "\"full_url\":\"https://example.com/report\",\"is_safe\":true,\"title\":\"Report\"}}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.reference");
        assertThat(events.getFirst().payload())
                .containsEntry("sourceType", "url_moderation")
                .containsEntry("referenceType", "url_moderation")
                .containsEntry("url", "https://example.com/report")
                .containsEntry("safe", true);
    }

    @Test
    void toolStructuredResultContentBecomesAssistantDeltaWithRelayContext() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"agent_name\":\"delegate_agent\","
                        + "\"tool_id\":\"tool-1\",\"parent_instance_id\":\"parent-1\","
                        + "\"timestamp\":\"2026-06-25T20:22:03.001964\","
                        + "\"session_id\":\"relay-session-1\","
                        + "\"result_data\":{\"widget\":{\"data\":{\"content\":\"知识\"}},"
                        + "\"index\":396,\"total\":403,\"is_last\":false}}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.delta");
        assertThat(events.getFirst().payload())
                .containsEntry("delta", "知识")
                .containsEntry("sourceType", "relay-content")
                .containsEntry("agentName", "delegate_agent")
                .containsEntry("toolId", "tool-1")
                .containsEntry("parentInstanceId", "parent-1")
                .containsEntry("runtimeSessionId", "relay-session-1")
                .containsEntry("index", 396)
                .containsEntry("total", 403)
                .containsEntry("isLast", false);
    }

    @Test
    void toolStructuredResultReferencesUseRelayPrefixedSourceTypes() {
        List<ChatEvent> searchEvents = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"result_data\":{\"widget\":{\"data\":{"
                        + "\"searchList\":[{\"title\":\"网页\",\"url\":\"https://example.com\"}]"
                        + "}},\"index\":1,\"total\":2}}");
        List<ChatEvent> sourceEvents = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"result_data\":{\"widget\":{\"data\":{"
                        + "\"sourcesDocuments\":[{\"name\":\"报告.pdf\"}]"
                        + "}}}}");

        assertThat(searchEvents).hasSize(1);
        assertThat(searchEvents.getFirst().type()).isEqualTo("runtime.reference");
        assertThat(searchEvents.getFirst().payload())
                .containsEntry("sourceType", "relay-searchList")
                .containsEntry("referenceType", "search_list");
        assertThat(sourceEvents).hasSize(1);
        assertThat(sourceEvents.getFirst().type()).isEqualTo("runtime.reference");
        assertThat(sourceEvents.getFirst().payload())
                .containsEntry("sourceType", "relay-sourcesDocuments")
                .containsEntry("referenceType", "source_documents");
    }

    @Test
    void toolStructuredResultProgressAndCardsMapToStandardRuntimeEvents() {
        List<ChatEvent> progressEvents = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"resultData\":{\"widget\":{\"data\":{"
                        + "\"processResult\":{\"dynamicResponse\":[{\"title\":\"正在调用工具\"}]}"
                        + "}},\"isLast\":\"true\"}}");
        List<ChatEvent> cardEvents = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"result_data\":{\"widget\":{\"data\":{"
                        + "\"diyCardScene\":{\"scene\":\"credit\"},\"skillId\":\"skill-1\""
                        + "}}}}");

        assertThat(progressEvents).hasSize(1);
        assertThat(progressEvents.getFirst().type()).isEqualTo("runtime.progress");
        assertThat(progressEvents.getFirst().payload())
                .containsEntry("sourceType", "relay-processResult")
                .containsEntry("status", "STREAMING")
                .containsEntry("text", "正在调用工具")
                .containsEntry("isLast", true);
        assertThat(cardEvents).hasSize(1);
        assertThat(cardEvents.getFirst().type()).isEqualTo("runtime.card");
        assertThat(cardEvents.getFirst().payload())
                .containsEntry("sourceType", "relay-diyCardScene")
                .containsEntry("cardType", "diyCardScene")
                .containsEntry("skillId", "skill-1");
    }

    @Test
    void toolStructuredResultDoesNotCopyAnswerContentIntoProgressText() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"result_data\":{\"widget\":{\"data\":{"
                        + "\"content\":\"正文答案\","
                        + "\"processResult\":{\"phase\":\"thinking\"}"
                        + "}}}}");

        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo("message.delta");
        assertThat(events.get(0).payload()).containsEntry("delta", "正文答案");
        assertThat(events.get(1).type()).isEqualTo("runtime.progress");
        assertThat(events.get(1).payload())
                .containsEntry("sourceType", "relay-processResult")
                .doesNotContainKey("text");
    }

    @Test
    void unrecognizedToolStructuredResultFallsBackWithRedaction() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"result_data\":{\"widget\":{\"data\":{"
                        + "\"unknown\":{\"authorization\":\"Bearer secret\",\"value\":\"ok\"}"
                        + "}}}}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.event");
        assertThat(events.getFirst().payload())
                .containsEntry("sourceType", "relay-tool-structured-result");
        assertThat(events.getFirst().payload().get("sourcePayload"))
                .asString()
                .doesNotContain("Bearer secret")
                .contains("[REDACTED]");
    }

    @Test
    void unknownJsonStillFallsBackToRuntimeEventWithRedaction() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
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
        assertThatThrownBy(() -> normalizer.normalize("run1", "session1",
                "{\"type\":\"error\",\"message\":\"relay failed\"}"))
                .isInstanceOf(RelayRuntimeProtocolException.class)
                .hasMessage("relay failed");
    }
}
