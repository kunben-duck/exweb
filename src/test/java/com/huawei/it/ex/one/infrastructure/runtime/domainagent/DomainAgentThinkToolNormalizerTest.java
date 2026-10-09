/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.runtime.domainagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class DomainAgentThinkToolNormalizerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DomainAgentResponseNormalizer normalizer = new DomainAgentResponseNormalizer(mapper);

    @Test
    void mapsAllSeventeenFramesInOrderWithoutAliasesOrSyntheticEvents() throws Exception {
        List<String> frames = List.of(
                "{\"type\":\"think\",\"thinkState\":\"start\",\"thinkTitle\":\"正在分析\",\"thinkContent\":\"我理解\"}",
                "{\"type\":\"think\",\"thinkContent\":\"您希望\"}",
                "{\"type\":\"think\",\"thinkContent\":\"分析202\"}",
                "{\"type\":\"think\",\"thinkContent\":\"6年\"}",
                "{\"type\":\"think\",\"thinkContent\":\"Q3\"}",
                "{\"type\":\"think\",\"thinkContent\":\"的销售数据\"}",
                "{\"type\":\"think\",\"thinkState\":\"stop\",\"thinkTime\":\"2026-10-08T02:41:49Z\",\"thinkTitle\":\"分析完成\",\"thinkContent\":\"已获取数据，开始生成回答...\"}",
                "{\"type\":\"tool\",\"toolTitle\":\"ls\",\"toolMatchRes\":\"['/appfile/', '/applog/', '/apps/']\"}",
                "{\"type\":\"tool\",\"toolTitle\":\"glob\",\"toolMatchRes\":\"[]\"}",
                "{\"type\":\"tool\",\"toolTitle\":\"ls\",\"toolMatchRes\":\"[]\"}",
                "{\"type\":\"tool\",\"toolTitle\":\"glob\",\"toolMatchRes\":\"[]\"}",
                "{\"type\":\"tool\",\"toolTitle\":\"glob\",\"toolMatchRes\":\"['/apps/demo/.auth/credentials.json', '/skills/demo/manifest.json']\"}",
                "{\"type\":\"think\",\"thinkState\":\"start\",\"thinkTitle\":\"正在分析\",\"thinkContent\":\"我\"}",
                "{\"type\":\"think\",\"thinkContent\":\"注意到\"}",
                "{\"type\":\"think\",\"thinkContent\":\"当前环境中\"}",
                "{\"type\":\"think\",\"thinkContent\":\"框架和方法\"}",
                "{\"type\":\"think\",\"thinkState\":\"stop\",\"thinkTime\":\"2026-10-08T02:42:16Z\",\"thinkTitle\":\"分析完成\",\"thinkContent\":\"已获取数据，开始生成回答...\"}");
        var state = normalizer.newStreamState();
        List<ChatEvent> events = normalizer.normalize("run1", "session1", frames.stream()
                .map(frame -> "data: " + frame + "\n\n").collect(Collectors.joining()), state);

        assertThat(events).hasSize(17);
        assertThat(events.stream().filter(event -> "runtime.thinking".equals(event.type()))).hasSize(12);
        assertThat(events.stream().filter(event -> "runtime.tool".equals(event.type()))).hasSize(5);
        for (int index = 0; index < frames.size(); index++) {
            Map<String, Object> expected = new LinkedHashMap<>(mapper.readValue(frames.get(index),
                    new TypeReference<Map<String, Object>>() { }));
            String type = (String) expected.get("type");
            expected.put("source", "domain-agent");
            expected.put("sourceType", type);
            ChatEvent event = events.get(index);
            assertThat(event.type()).isEqualTo("think".equals(type) ? "runtime.thinking" : "runtime.tool");
            assertThat(event.payload()).isEqualTo(expected)
                    .doesNotContainKeys("status", "title", "text", "toolName", "inputPreview");
            assertThat(event.runId()).isEqualTo("run1");
            assertThat(event.sessionId()).isEqualTo("session1");
        }
        assertThat(normalizer.finish("run1", "session1", state)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"think\",\"thinkState\":\"start\"}",
            "{\"type\":\"think\",\"thinkState\":\"stop\"}",
            "{\"type\":\"think\",\"thinkTitle\":\"分析\"}",
            "{\"type\":\"think\",\"thinkState\":null,\"thinkContent\":\"分析\",\"thinkTime\":null}",
            "{\"type\":\"think\",\"thinkState\":\"  \",\"thinkContent\":\" 分析 \",\"thinkTitle\":\"\"}"
    })
    void preservesPresentFieldsAndOmitsOnlyNulls(String frame) throws Exception {
        Map<String, Object> expected = mapper.readValue(frame, new TypeReference<Map<String, Object>>() { });
        expected.values().removeIf(java.util.Objects::isNull);
        expected.put("source", "domain-agent");
        expected.put("sourceType", "think");
        assertThat(normalizer.normalize("r", "s", frame)).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("runtime.thinking");
            assertThat(event.payload()).isEqualTo(expected);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"think\"}", "{\"type\":\"think\",\"thinkContent\":\"  \"}",
            "{\"type\":\"think\",\"thinkContent\":42}",
            "{\"type\":\"think\",\"thinkState\":\"future\",\"thinkContent\":\"data\"}",
            "{\"type\":\"think\",\"thinkState\":true,\"thinkTitle\":\"data\"}",
            "{\"type\":\"THINK\",\"thinkContent\":\"data\"}",
            "{\"thinkContent\":\"data\"}", "{\"type\":\"think\",\"think_content\":\"data\"}",
            "{\"type\":\"tool\"}", "{\"type\":\"tool\",\"toolTitle\":\"  \"}",
            "{\"type\":\"tool\",\"toolTitle\":42}", "{\"toolTitle\":\"ls\"}"
    })
    void unsupportedFormatsKeepExistingFallback(String frame) {
        assertThat(normalizer.normalize("r", "s", frame)).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("runtime.event");
            assertThat(event.payload()).containsEntry("sourceType", "unknown");
        });
    }

    @Test
    void businessPayloadIsNotDiagnosticTruncatedAndNestedSensitiveFieldsAreRedacted() {
        String content = "思考".repeat(1800);
        ObjectNode think = mapper.createObjectNode().put("type", "think").put("thinkContent", content);
        assertThat(normalizer.normalize("r", "s", think.toString()).getFirst().payload())
                .containsEntry("thinkContent", content);
        ObjectNode tool = mapper.createObjectNode().put("type", "tool").put("toolTitle", "ls");
        tool.set("toolMatchRes", mapper.valueToTree(Map.of("results", List.of("b", "a"),
                "token", "secret", "nested", Map.of("password", "secret", "count", 2), "content", content)));
        assertThat(normalizer.normalize("r", "s", tool.toString()).getFirst().payload())
                .containsEntry("toolMatchRes", Map.of("results", List.of("b", "a"), "token", "[REDACTED]",
                        "nested", Map.of("password", "[REDACTED]", "count", 2), "content", content));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}", "42", "true", "\"[]\"", "null"})
    void toolResultKeepsItsJsonType(String value) throws Exception {
        ChatEvent event = normalizer.normalize("r", "s",
                "{\"type\":\"tool\",\"toolTitle\":\"glob\",\"toolMatchRes\":" + value + "}").getFirst();
        assertThat(event.type()).isEqualTo("runtime.tool");
        if ("null".equals(value)) {
            assertThat(event.payload()).doesNotContainKey("toolMatchRes");
        } else {
            JsonNode result = mapper.valueToTree(event.payload().get("toolMatchRes"));
            assertThat(result).isEqualTo(mapper.readTree(value));
        }
    }

    @Test
    void splitFramesAndMixedFieldsRetainMetadataStateCardContentAndCompletionOrder() {
        String frame = "data: {\"type\":\"think\",\"thinkState\":\"start\",\"thinkContent\":\"分析\","
                + "\"state\":\"THINKING\",\"sessionId\":\"downstream\",\"cardUrl\":\"https://example.test/card.js\","
                + "\"content\":\"answer\",\"endFlag\":true}\n\n";
        for (int split = 6; split < frame.indexOf('}'); split++) {
            var state = normalizer.newStreamState();
            assertThat(normalizer.normalize("r", "s", frame.substring(0, split), state)).isEmpty();
            List<ChatEvent> events = normalizer.normalize("r", "s", frame.substring(split), state);
            assertThat(events).extracting(ChatEvent::type).containsExactly(
                    "runtime.metadata", "runtime.thinking", "runtime.card", "message.delta", "message.completed");
            assertThat(events.getFirst().payload()).containsEntry("runtimeSessionId", "downstream");
            assertThat(events.get(1).payload()).containsEntry("sourceType", "think");
            assertThat(events).allSatisfy(event -> assertThat(event.sessionId()).isEqualTo("s"));
        }
    }

    @Test
    void newBusinessFramesStillRespectFrameCapacity() {
        ObjectNode frame = mapper.createObjectNode().put("type", "tool").put("toolTitle", "ls")
                .put("toolMatchRes", "x".repeat(256 * 1024));
        assertThatThrownBy(() -> normalizer.normalize("r", "s", frame.toString()))
                .isInstanceOf(DomainAgentProtocolException.class);
    }
}
