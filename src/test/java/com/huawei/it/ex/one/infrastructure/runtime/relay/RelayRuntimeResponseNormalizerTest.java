package com.huawei.it.ex.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RelayRuntimeResponseNormalizerTest {
    private final RelayRuntimeResponseNormalizer normalizer = new RelayRuntimeResponseNormalizer(new ObjectMapper());

    @Test
    void protocolNormalizationAndSensitiveFieldsDoNotDependOnDefaultLocale() {
        Locale previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            List<ChatEvent> events = normalizer.normalize("run1", "session1",
                    "{\"type\":\"THINKING_CONTENT_UPDATE\",\"content\":\"analysis\","
                            + "\"AUTHORIZATION\":\"secret\"}");

            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.type()).isEqualTo("runtime.thinking");
                assertThat(event.payload())
                        .containsEntry("sourceType", "THINKING_CONTENT_UPDATE")
                        .containsEntry("AUTHORIZATION", "[REDACTED]");
            });
        } finally {
            Locale.setDefault(previousLocale);
        }
    }

    @Test
    void plainTextChunkIsTranslatedToDeltaEvent() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", "hello");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.delta");
        assertThat(events.getFirst().payload()).containsEntry("delta", "hello");
    }

    @Test
    void jsonNodeShapesKeepExistingNormalizationSemantics() {
        assertThat(normalizer.normalize("run1", "session1", "null")).isEmpty();

        List<ChatEvent> textualEvents = normalizer.normalize("run1", "session1", "\"hello\"");
        assertThat(textualEvents).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("message.delta");
            assertThat(event.payload()).containsEntry("delta", "hello");
        });

        assertThatThrownBy(() -> normalizer.normalize("run1", "session1", "[]"))
                .isInstanceOf(RelayRuntimeProtocolException.class)
                .hasMessageContaining("Unsupported Relay runtime frame shape");
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
    void sessionReadyBecomesRuntimeMetadataWithRuntimeSessionId() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\",\"session_mode\":\"new\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.metadata");
        assertThat(events.getFirst().payload())
                .containsEntry("sourceType", "session-ready")
                .containsEntry("runtimeSessionId", "relay-session-1")
                .containsEntry("session_id", "relay-session-1")
                .containsEntry("session_mode", "new");
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
                .containsEntry("agent_name", "delegate_agent")
                .doesNotContainKey("agentName")
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
                .containsEntry("agent_name", "delegate_agent")
                .doesNotContainKey("agentName")
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
    void generateResponseContentBecomesFinalSnapshot() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"generate-response\",\"agent_name\":\"delegate_agent\","
                        + "\"content\":\"完整总结\",\"is_final\":true,\"instance_id\":\"inst-1\","
                        + "\"session_id\":\"relay-session-1\",\"version_id\":16}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.snapshot");
        assertThat(events.getFirst().payload())
                .containsEntry("content", "完整总结")
                .containsEntry("sourceType", "generate-response")
                .containsEntry("agent_name", "delegate_agent")
                .containsEntry("runtimeSessionId", "relay-session-1")
                .containsEntry("instance_id", "inst-1")
                .containsEntry("version_id", 16)
                .containsEntry("is_final", true)
                .doesNotContainKey("agentName")
                .doesNotContainKey("versionId");
    }

    @Test
    void generateResponseWithoutContentStaysRuntimeProgress() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"generate-response\",\"message\":\"生成完成\",\"is_final\":true}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.progress");
        assertThat(events.getFirst().payload())
                .containsEntry("sourceType", "generate-response")
                .containsEntry("message", "生成完成")
                .doesNotContainKey("text");
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
                .containsEntry("content", "处理中")
                .containsEntry("instansid", "relay-session-1")
                .doesNotContainKey("runtimeSessionId")
                .doesNotContainKey("text");
        assertThat(toolEvents).hasSize(1);
        assertThat(toolEvents.getFirst().type()).isEqualTo("runtime.tool");
        assertThat(toolEvents.getFirst().payload())
                .containsEntry("too_name", "eureka_chat")
                .containsEntry("input_preview", "查询报销流程")
                .doesNotContainKey("toolName")
                .doesNotContainKey("inputPreview");
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
                .containsEntry("agent_name", "PlanAgent")
                .containsEntry("is_start", true)
                .containsEntry("thought", "分析问题")
                .doesNotContainKey("status");
        assertThat(thinkingContentEvents).hasSize(1);
        assertThat(thinkingContentEvents.getFirst().type()).isEqualTo("runtime.thinking");
        assertThat(thinkingContentEvents.getFirst().payload())
                .containsEntry("sourceType", "thinking-content-update")
                .containsEntry("content", "思考片段")
                .containsEntry("operation_id", "op-1")
                .doesNotContainKey("operationId");
        assertThat(toolExecutionEvents).hasSize(1);
        assertThat(toolExecutionEvents.getFirst().type()).isEqualTo("runtime.tool");
        assertThat(toolExecutionEvents.getFirst().payload())
                .containsEntry("sourceType", "tool-execution")
                .containsEntry("tool_name", "mcp__x__search")
                .containsEntry("tool_id", "tool-1")
                .containsEntry("is_start", false)
                .containsEntry("result_summary", "完成")
                .doesNotContainKey("toolName");
    }

    @Test
    void allRelayThinkingAliasesBecomeRuntimeThinkingEvents() {
        for (String sourceType : List.of(
                "agent-reasoning",
                "thinking-operation-start",
                "thinkink-operation-start",
                "thinking-content-update",
                "thinking-operation-end",
                "thinking-operation-finish")) {
            List<ChatEvent> events = normalizer.normalize("run1", "session1",
                    "{\"type\":\"" + sourceType + "\",\"content\":\"thinking\"}");

            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.type()).isEqualTo("runtime.thinking");
                assertThat(event.payload()).containsEntry("sourceType", sourceType);
            });
        }
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
                .containsEntry("state", "idle")
                .containsEntry("session_id", "relay-session-1")
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
                .containsKey("url_moderation_result")
                .doesNotContainKey("referenceType")
                .doesNotContainKey("url");
    }

    @Test
    void toolStructuredResultBecomesRuntimeToolWithRawPayload() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"agent_name\":\"delegate_agent\","
                        + "\"tool_id\":\"tool-1\",\"parent_instance_id\":\"parent-1\","
                        + "\"timestamp\":\"2026-06-25T20:22:03.001964\","
                        + "\"session_id\":\"relay-session-1\","
                        + "\"result_data\":{\"widget\":{\"data\":{\"content\":\"知识\"}},"
                        + "\"index\":396,\"total\":403,\"is_last\":false}}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.tool");
        assertThat(events.getFirst().payload())
                .containsEntry("sourceType", "tool-structured-result")
                .containsEntry("agent_name", "delegate_agent")
                .containsEntry("tool_id", "tool-1")
                .containsEntry("parent_instance_id", "parent-1")
                .containsEntry("session_id", "relay-session-1")
                .containsEntry("runtimeSessionId", "relay-session-1")
                .containsKey("result_data")
                .doesNotContainKey("delta")
                .doesNotContainKey("toolId")
                .doesNotContainKey("isLast");
    }

    @Test
    void toolStructuredResultReferencesKeepRawResultData() {
        List<ChatEvent> searchEvents = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"result_data\":{\"widget\":{\"data\":{"
                        + "\"searchList\":[{\"title\":\"网页\",\"url\":\"https://example.com\"}]"
                        + "}},\"index\":1,\"total\":2}}");
        List<ChatEvent> sourceEvents = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"result_data\":{\"widget\":{\"data\":{"
                        + "\"sourcesDocuments\":[{\"name\":\"报告.pdf\"}]"
                        + "}}}}");

        assertThat(searchEvents).hasSize(1);
        assertThat(searchEvents.getFirst().type()).isEqualTo("runtime.tool");
        assertThat(searchEvents.getFirst().payload())
                .containsEntry("sourceType", "tool-structured-result")
                .containsKey("result_data")
                .doesNotContainKey("referenceType");
        assertThat(sourceEvents).hasSize(1);
        assertThat(sourceEvents.getFirst().type()).isEqualTo("runtime.tool");
        assertThat(sourceEvents.getFirst().payload())
                .containsEntry("sourceType", "tool-structured-result")
                .containsKey("result_data")
                .doesNotContainKey("referenceType");
    }

    @Test
    void toolStructuredResultProgressAndCardsStayRuntimeTool() {
        List<ChatEvent> progressEvents = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"resultData\":{\"widget\":{\"data\":{"
                        + "\"processResult\":{\"dynamicResponse\":[{\"title\":\"正在调用工具\"}]}"
                        + "}},\"isLast\":\"true\"}}");
        List<ChatEvent> cardEvents = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"result_data\":{\"widget\":{\"data\":{"
                        + "\"diyCardScene\":{\"scene\":\"credit\"},\"skillId\":\"skill-1\""
                        + "}}}}");

        assertThat(progressEvents).hasSize(1);
        assertThat(progressEvents.getFirst().type()).isEqualTo("runtime.tool");
        assertThat(progressEvents.getFirst().payload())
                .containsEntry("sourceType", "tool-structured-result")
                .containsKey("resultData")
                .doesNotContainKey("text")
                .doesNotContainKey("isLast");
        assertThat(cardEvents).hasSize(1);
        assertThat(cardEvents.getFirst().type()).isEqualTo("runtime.tool");
        assertThat(cardEvents.getFirst().payload())
                .containsEntry("sourceType", "tool-structured-result")
                .containsKey("result_data")
                .doesNotContainKey("cardType")
                .doesNotContainKey("skillId");
    }

    @Test
    void toolStructuredResultDoesNotSplitContentOrProcessResult() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"result_data\":{\"widget\":{\"data\":{"
                        + "\"content\":\"正文答案\","
                        + "\"processResult\":{\"phase\":\"thinking\"}"
                        + "}}}}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.tool");
        assertThat(events.getFirst().payload())
                .containsEntry("sourceType", "tool-structured-result")
                .containsKey("result_data")
                .doesNotContainKey("delta")
                .doesNotContainKey("text");
    }

    @Test
    void toolStructuredResultKeepsRawPayloadWithRedaction() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"result_data\":{\"widget\":{\"data\":{"
                        + "\"unknown\":{\"authorization\":\"Bearer secret\",\"value\":\"ok\"}"
                        + "}}}}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.tool");
        assertThat(events.getFirst().payload())
                .containsEntry("sourceType", "tool-structured-result")
                .containsKey("result_data");
        assertThat(events.getFirst().payload().get("result_data"))
                .asString()
                .doesNotContain("Bearer secret")
                .contains("[REDACTED]");
    }

    @Test
    void toolStructuredResultKeepsDeepDynamicResponseWithoutTruncation() {
        String longText = "x".repeat(2100);
        StringBuilder dynamicResponse = new StringBuilder("[");
        for (int i = 0; i < 60; i++) {
            if (i > 0) {
                dynamicResponse.append(',');
            }
            dynamicResponse.append('"').append("第").append(i).append("段");
            if (i == 59) {
                dynamicResponse.append(longText);
            }
            dynamicResponse.append('"');
        }
        dynamicResponse.append(']');
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"tool-structured-result\",\"result_data\":{\"widget\":{\"data\":{"
                        + "\"processResult\":{\"dynamicResponse\":" + dynamicResponse + ","
                        + "\"fixedResponse\":\"为您找到18条文章，引用文章10条\"}"
                        + "}},\"index\":8,\"total\":690,\"is_last\":false}}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.tool");
        Map<String, Object> payload = events.getFirst().payload();
        Map<String, Object> resultData = asMap(payload.get("result_data"));
        Map<String, Object> widget = asMap(resultData.get("widget"));
        Map<String, Object> data = asMap(widget.get("data"));
        Map<String, Object> processResult = asMap(data.get("processResult"));
        assertThat((List<?>) processResult.get("dynamicResponse"))
                .hasSize(60)
                .first()
                .isEqualTo("第0段");
        assertThat((List<?>) processResult.get("dynamicResponse"))
                .element(59)
                .isEqualTo("第59段" + longText);
        assertThat(processResult)
                .containsEntry("fixedResponse", "为您找到18条文章，引用文章10条");
        assertThat(payload.toString()).doesNotContain("[TRUNCATED]");
    }

    @Test
    void unknownJsonStillFallsBackToRuntimeEventWithRedaction() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "{\"type\":\"custom-event\",\"authorization\":\"Bearer secret\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.event");
        assertThat(events.getFirst().payload().get("authorization"))
                .asString()
                .doesNotContain("Bearer secret")
                .contains("[REDACTED]");
    }

    @Test
    void nullableJsonValuesArePreservedForKnownSnapshotAndFallbackEvents() {
        ChatEvent progress = normalizer.normalize("run1", "session1",
                "{\"type\":\"relay-progress\",\"detail\":null,\"nested\":{\"value\":null}}")
                .getFirst();
        ChatEvent snapshot = normalizer.normalize("run1", "session1",
                "{\"type\":\"agent\",\"is_streaming\":false,\"content\":\"answer\",\"detail\":null}")
                .getFirst();
        ChatEvent fallback = normalizer.normalize("run1", "session1",
                "{\"type\":\"future-event\",\"detail\":null}")
                .getFirst();

        assertThat(progress.type()).isEqualTo("runtime.progress");
        assertThat(snapshot.type()).isEqualTo("message.snapshot");
        assertThat(fallback.type()).isEqualTo("runtime.event");
        assertThat(progress.payload()).containsKey("detail");
        assertThat(progress.payload().get("detail")).isNull();
        assertThat(asMap(progress.payload().get("nested")).get("value")).isNull();
        assertThat(snapshot.payload()).containsKey("detail");
        assertThat(snapshot.payload().get("detail")).isNull();
        assertThat(fallback.payload()).containsKey("detail");
        assertThat(fallback.payload().get("detail")).isNull();
    }

    @Test
    void errorFrameFailsProtocol() {
        assertThatThrownBy(() -> normalizer.normalize("run1", "session1",
                "{\"type\":\"error\",\"message\":\"relay failed\"}"))
                .isInstanceOf(RelayRuntimeProtocolException.class)
                .hasMessage("relay failed");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
