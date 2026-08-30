/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.runtime.domainagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.domain.chat.ChatEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class DomainAgentResponseNormalizerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DomainAgentResponseNormalizer normalizer = new DomainAgentResponseNormalizer(objectMapper);

    @Test
    void defaultsStructuredFrameLimitTo256KiB() {
        DomainAgentProperties properties = new DomainAgentProperties();

        assertThat(properties.getMaxPendingFrameBytes()).isEqualTo(256 * 1024);
        assertThat(properties.normalizedMaxPendingFrameBytes()).isEqualTo(256 * 1024);

        properties.setMaxPendingFrameBytes(0);
        assertThat(properties.normalizedMaxPendingFrameBytes()).isEqualTo(256 * 1024);
    }

    @Test
    void mapsOutOfDomainRefusalToStableControlEventAndPreservesNullValues() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"type":"agent.refusal","code":"FN-EX-CAHT-BIZ-DAG-001",\
                "agentId":"tax-agent","reasonCode":"OUT_OF_DOMAIN","recoverable":false,\
                "reason":null,"metadata":{"goal":null},"endFlag":true}
                """);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.metadata");
        assertThat(events.getFirst().payload())
                .containsEntry("source", "domain-agent")
                .containsEntry("sourceType", "agent.refusal")
                .containsEntry("metadataType", "domain_agent_control")
                .containsEntry("supervisorAction", "REROUTE")
                .containsEntry("code", "FN-EX-CAHT-BIZ-DAG-001")
                .containsEntry("reasonCode", "OUT_OF_DOMAIN")
                .containsEntry("recoverable", false)
                .containsKey("reason");
        assertThat(events.getFirst().payload().get("reason")).isNull();
        assertThat(events.getFirst().payload().get("metadata")).isInstanceOfSatisfying(
                java.util.Map.class,
                metadata -> assertThat(metadata).containsKey("goal"));
    }

    @Test
    void keepsUnknownRefusalAsUnhandledControlEventWithoutTriggeringKnownAction() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"type":"agent.refusal","code":"FN-EX-CAHT-BIZ-DAG-999",\
                "reasonCode":"OTHER","recoverable":false,"endFlag":true}
                """);

        assertThat(events).extracting(ChatEvent::type)
                .containsExactly("runtime.metadata", "message.completed");
        assertThat(events.getFirst().payload()).containsEntry("supervisorAction", "UNHANDLED");
    }

    @Test
    void mapsDomainAgentEventStreamFramesToChatServiceEvents() {
        String chunk = """
                message: {"traceId":"trace-1"}

                message: {"sessionId":"domain-session-1"}

                message: {"messageId":"domain-message-1"}

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
                "runtime.card",
                "runtime.progress",
                "message.delta",
                "message.completed"
        );
        assertThat(events.get(0).payload()).containsEntry("metadataType", "trace")
                .containsEntry("traceId", "trace-1");
        assertThat(events.get(1).payload()).containsEntry("metadataType", "domain_agent_session")
                .containsEntry("domainAgentSessionId", "domain-session-1");
        assertThat(events.get(2).payload()).containsEntry("metadataType", "domain_agent_message")
                .containsEntry("domainAgentMessageId", "domain-message-1");
        assertThat(events.get(3).payload()).containsEntry("cardUrl", "https://card")
                .containsEntry("sourceType", "cardUrl")
                .containsEntry("cardType", "url")
                .containsEntry("intent", "tax")
                .containsEntry("domainAgentId", "skill-tax");
        assertThat(events.get(5).payload()).containsEntry("delta", "你好");
        assertThat(events.get(6).payload()).containsEntry("sourceType", "domain-agent-end");
    }

    @Test
    void mapsStandaloneIntentAndSkillToMetadata() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "message: {\"intent\":\"CreditSales\",\"skillId\":\"skill-credit\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.metadata");
        assertThat(events.getFirst().payload()).containsEntry("metadataType", "domain_agent")
                .containsEntry("intent", "CreditSales")
                .containsEntry("domainAgentId", "skill-credit");
    }

    @Test
    void unknownJsonFallsBackToRuntimeEvent() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", "message: {\"foo\":\"bar\"}");

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("runtime.event");
        assertThat(events.get(0).payload()).containsEntry("source", "domain-agent");
    }

    @Test
    void mapsEnabledAsyncStartedFrameToRunBoundaryWithoutCompletingMessage() {
        DomainAgentProperties properties = new DomainAgentProperties();
        properties.setAsyncTaskEnabled(true);
        DomainAgentResponseNormalizer asyncNormalizer =
                new DomainAgentResponseNormalizer(objectMapper, properties);

        List<ChatEvent> events = asyncNormalizer.normalize("run1", "session1", """
                message: {"type":"agent.async_started","message":"任务已转入后台执行"}

                """);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("run.async_running");
        assertThat(events.getFirst().payload())
                .containsEntry("status", "ASYNC_RUNNING")
                .containsEntry("message", "任务已转入后台执行");
    }

    @Test
    void rejectsNestedAsyncStartedCallbackFrame() throws Exception {
        DomainAgentProperties properties = new DomainAgentProperties();
        properties.setAsyncTaskEnabled(true);
        DomainAgentResponseNormalizer asyncNormalizer =
                new DomainAgentResponseNormalizer(objectMapper, properties);

        assertThatThrownBy(() -> asyncNormalizer.normalizeCallbackFrame(
                "run1", "session1", objectMapper.readTree("{\"type\":\"agent.async_started\"}"),
                asyncNormalizer.newStreamState()))
                .isInstanceOf(DomainAgentProtocolException.class)
                .hasMessageContaining("cannot start another async task");
    }

    @Test
    void supportsDomainAgentMessagePrefixesAndRedactsSensitiveUnknownPayload() {
        String chunk = """
                message. {"content":"A"}

                message {"token":"secret","foo":"bar"}

                """;

        List<ChatEvent> events = normalizer.normalize("run1", "session1", chunk);

        assertThat(events).extracting(ChatEvent::type).containsExactly("message.delta", "runtime.event");
        assertThat(events.get(0).payload()).containsEntry("delta", "A");
        assertThat(events.get(1).payload().toString()).contains("[REDACTED]").doesNotContain("secret");
    }

    @Test
    void mapsThinkingReferencesStatesAndCards() {
        String chunk = """
                message: {"state":"THINKING","stateDesc":"思考中"}

                message: {"searchList":[{"title":"网页","url":"https://example.com"}]}

                message: {"sourcesDocuments":[{"docName":"a.pdf"}]}

                message: {"state":"GENERATE","stateDesc":"生成答案"}

                message: {"diyCardScene":{"type":"tax"}}

                """;

        List<ChatEvent> events = normalizer.normalize("run1", "session1", chunk);

        assertThat(events).extracting(ChatEvent::type).containsExactly(
                "runtime.thinking",
                "runtime.reference",
                "runtime.reference",
                "runtime.progress",
                "runtime.card"
        );
        assertThat(events.get(0).payload()).containsEntry("status", "STARTED")
                .containsEntry("stateDesc", "思考中");
        assertThat(events.get(1).payload()).containsEntry("referenceType", "search_list")
                .doesNotContainKey("metadata");
        assertThat(events.get(2).payload()).containsEntry("referenceType", "source_documents");
        assertThat(events.get(3).payload()).containsEntry("stage", "GENERATE");
        assertThat(events.get(4).payload()).containsEntry("sourceType", "diyCardScene")
                .containsEntry("cardType", "diyCardScene")
                .containsEntry("cardSources", List.of("diyCardScene"));
    }

    @Test
    void preservesSearchListSiblingMetadataInReferencePayload() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                data: {"searchList":[{"doc_id":"w3_20596973_CN_W3","index":16,"is_full":true,\
                "title":"任命通知（子公司CFO）","content":"任命正文"}],"metadata":{\
                "knowLevel":["MIP","CIP","IIP"],"knowMapping":[{"type":"MIP","name":"作业依据"},\
                {"type":"CIP","name":"组织资产"},{"type":"IIP","name":"其他"}],\
                "extension":{"apiToken":"secret","enabled":true}}}
                """);

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.reference");
        assertThat(events.getFirst().payload())
                .containsEntry("source", "domain-agent")
                .containsEntry("sourceType", "searchList")
                .containsEntry("referenceType", "search_list")
                .containsEntry("references", List.of(Map.of(
                        "doc_id", "w3_20596973_CN_W3",
                        "index", 16,
                        "is_full", true,
                        "title", "任命通知（子公司CFO）",
                        "content", "任命正文"
                )))
                .containsEntry("metadata", Map.of(
                        "knowLevel", List.of("MIP", "CIP", "IIP"),
                        "knowMapping", List.of(
                                Map.of("type", "MIP", "name", "作业依据"),
                                Map.of("type", "CIP", "name", "组织资产"),
                                Map.of("type", "IIP", "name", "其他")
                        ),
                        "extension", Map.of("apiToken", "[REDACTED]", "enabled", true)
                ));
    }

    @Test
    void handlesAbsentNullAndEmptySearchListMetadataWithoutChangingOtherReferences() {
        List<ChatEvent> absent = normalizer.normalize("run1", "session1",
                "data: {\"searchList\":[{\"title\":\"网页\"}]}");
        List<ChatEvent> nullMetadata = normalizer.normalize("run1", "session1",
                "data: {\"searchList\":[{\"title\":\"网页\"}],\"metadata\":null}");
        List<ChatEvent> emptyMetadata = normalizer.normalize("run1", "session1",
                "data: {\"searchList\":[{\"title\":\"网页\"}],\"metadata\":{}}");
        List<ChatEvent> sourceDocuments = normalizer.normalize("run1", "session1",
                "data: {\"sourcesDocuments\":[{\"docName\":\"制度.pdf\"}],"
                        + "\"metadata\":{\"knowLevel\":[\"MIP\"]}}");

        assertThat(absent.getFirst().payload()).doesNotContainKey("metadata");
        assertThat(nullMetadata.getFirst().payload()).doesNotContainKey("metadata");
        assertThat(emptyMetadata.getFirst().payload()).containsEntry("metadata", Map.of());
        assertThat(sourceDocuments.getFirst().payload())
                .containsEntry("sourceType", "sourcesDocuments")
                .doesNotContainKey("metadata");
    }

    @Test
    void buffersSplitDiyCardSceneAndEmitsOneCompleteEvent() {
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();

        List<ChatEvent> first = normalizer.normalize("run1", "session1",
                "message: {\"diyCardScene\":{\"title\":\"税务", state);
        List<ChatEvent> second = normalizer.normalize("run1", "session1",
                "意见\",\"items\":[{\"name\":\"A\"}]}}", state);

        assertThat(first).isEmpty();
        assertThat(second).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(second.getFirst().payload()).containsEntry("sourceType", "diyCardScene")
                .doesNotContainKeys("fragment", "itemId", "delta", "complete");
        assertThat(second.getFirst().payload().get("diyCardScene")).asString()
                .contains("税务意见", "items");
        assertThat(second).extracting(ChatEvent::type).doesNotContain("runtime.event");
    }

    @Test
    void buffersSplitReferencesWithoutRequiringFieldNameInLaterChunks() {
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();

        List<ChatEvent> first = normalizer.normalize("run1", "session1",
                "message: {\"sourcesDocuments\":[{\"docName\":\"制度", state);
        List<ChatEvent> second = normalizer.normalize("run1", "session1",
                "文件\",\"url\":\"https://example.com/a.pdf\"}]}", state);

        assertThat(first).isEmpty();
        assertThat(second).extracting(ChatEvent::type).containsExactly("runtime.reference");
        assertThat(second.getFirst().payload()).containsEntry("sourceType", "sourcesDocuments")
                .doesNotContainKeys("fragment", "itemId", "delta", "complete");
        assertThat(second.getFirst().payload().get("references")).asString()
                .contains("制度文件", "https://example.com/a.pdf");
    }

    @Test
    void buffersSplitSearchListWithoutInvalidJson() {
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();

        List<ChatEvent> first = normalizer.normalize("run1", "session1",
                "message: {\"searchList\":[{\"title\":\"网页", state);
        List<ChatEvent> second = normalizer.normalize("run1", "session1",
                "资料\",\"url\":\"https://example.com\"}]}", state);

        assertThat(first).isEmpty();
        assertThat(second).extracting(ChatEvent::type).containsExactly("runtime.reference");
        assertThat(second.getFirst().payload()).containsEntry("sourceType", "searchList")
                .doesNotContainKeys("fragment", "itemId", "delta", "complete");
        assertThat(second.getFirst().payload().get("references")).asString()
                .contains("网页资料", "https://example.com");
    }

    @Test
    void buffersSplitProcessResultAndEmitsOneCompleteProgressEvent() {
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();

        List<ChatEvent> first = normalizer.normalize("run1", "session1",
                "message: {\"processResult\":{\"dynamicResponse\":[{\"title\":\"工具", state);
        List<ChatEvent> second = normalizer.normalize("run1", "session1",
                "调用\",\"type\":\"str\"}]}}", state);

        assertThat(first).isEmpty();
        assertThat(second).extracting(ChatEvent::type).containsExactly("runtime.progress");
        assertThat(second.getFirst().payload()).containsEntry("sourceType", "processResult")
                .doesNotContainKeys("fragment", "itemId", "delta", "complete");
        assertThat(second.getFirst().payload().get("processResult")).asString()
                .contains("工具调用", "dynamicResponse");
    }

    @Test
    void preservesWhitespaceOnlyChunkInsideStructuredString() {
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();

        assertThat(normalizer.normalize("run1", "session1",
                "message: {\"processResult\":{\"fixedResponse\":\"left", state)).isEmpty();
        assertThat(normalizer.normalize("run1", "session1", "   ", state)).isEmpty();
        List<ChatEvent> events = normalizer.normalize("run1", "session1", "right\"}}", state);

        Map<?, ?> processResult = asMap(events.getFirst().payload().get("processResult"));
        assertThat(processResult.get("fixedResponse")).isEqualTo("left   right");
    }

    @Test
    void preservesCompleteLongBusinessPayloadAndRedactsSensitiveFields() throws Exception {
        String fixedResponse = "<svg>" + "x".repeat(4079) + "</svg>";
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode processResult = root.putObject("processResult");
        processResult.put("fixedResponse", fixedResponse);
        processResult.put("accessToken", "secret-token");

        List<ChatEvent> events = normalizer.normalize(
                "run1", "session1", "message: " + objectMapper.writeValueAsString(root));

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.progress");
        Map<?, ?> normalized = asMap(events.getFirst().payload().get("processResult"));
        assertThat(normalized.get("fixedResponse")).isEqualTo(fixedResponse);
        assertThat(normalized.get("accessToken")).isEqualTo("[REDACTED]");
    }

    @Test
    void acceptsCompleteStructuredFrameNearDefault256KiBLimit() throws Exception {
        String fixedResponse = "x".repeat(240_000);
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("processResult").put("fixedResponse", fixedResponse);

        List<ChatEvent> events = normalizer.normalize(
                "run1", "session1", "message: " + objectMapper.writeValueAsString(root));

        Map<?, ?> normalized = asMap(events.getFirst().payload().get("processResult"));
        assertThat(normalized.get("fixedResponse")).isEqualTo(fixedResponse);
    }

    @Test
    void preservesAllBusinessArrayItemsBeyondFormerLimit() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode dynamicResponse = root.putObject("processResult").putArray("dynamicResponse");
        for (int i = 0; i < 75; i++) {
            dynamicResponse.addObject().put("title", "item-" + i).put("type", "str");
        }

        List<ChatEvent> events = normalizer.normalize(
                "run1", "session1", "message: " + objectMapper.writeValueAsString(root));

        Map<?, ?> processResult = asMap(events.getFirst().payload().get("processResult"));
        assertThat((List<?>) processResult.get("dynamicResponse"))
                .hasSize(75)
                .noneMatch("[TRUNCATED]"::equals);
    }

    @Test
    void rejectsCompleteFrameAboveConfiguredByteLimit() throws Exception {
        DomainAgentProperties properties = new DomainAgentProperties();
        properties.setMaxPendingFrameBytes(128);
        DomainAgentResponseNormalizer limited = new DomainAgentResponseNormalizer(objectMapper, properties);
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("processResult").put("fixedResponse", "x".repeat(200));

        assertThatThrownBy(() -> limited.normalize(
                "run1", "session1", "message: " + objectMapper.writeValueAsString(root)))
                .isInstanceOf(DomainAgentProtocolException.class)
                .hasMessageContaining("DOMAIN_AGENT_FRAME_TOO_LARGE")
                .hasMessageContaining("maxBytes=128");
    }

    @Test
    void rejectsIncompleteFrameAsSoonAsConfiguredByteLimitIsExceeded() {
        DomainAgentProperties properties = new DomainAgentProperties();
        properties.setMaxPendingFrameBytes(128);
        DomainAgentResponseNormalizer limited = new DomainAgentResponseNormalizer(objectMapper, properties);
        DomainAgentResponseNormalizer.DomainAgentStreamState state = limited.newStreamState();

        assertThat(limited.normalize("run1", "session1",
                "message: {\"processResult\":{\"fixedResponse\":\"", state)).isEmpty();
        assertThatThrownBy(() -> limited.normalize("run1", "session1", "x".repeat(200), state))
                .isInstanceOf(DomainAgentProtocolException.class)
                .hasMessageContaining("DOMAIN_AGENT_FRAME_TOO_LARGE");
    }

    @Test
    void keepsDiagnosticTextBounded() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("unknownField", "x".repeat(3000));

        List<ChatEvent> events = normalizer.normalize(
                "run1", "session1", "message: " + objectMapper.writeValueAsString(root));

        Map<?, ?> sourcePayload = asMap(events.getFirst().payload().get("sourcePayload"));
        Map<?, ?> diagnostic = asMap(sourcePayload.get("sourcePayload"));
        assertThat(String.valueOf(diagnostic.get("unknownField"))).hasSize(2048);
    }

    @Test
    void skipsNullItemsInStructuredArrays() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"sourcesDocuments":[null,{"docName":"a.pdf"}],"cardList":[null,{"title":"卡片"}]}
                """);

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.reference", "runtime.card");
        assertThat(events.get(0).payload().toString()).contains("a.pdf").doesNotContain("null");
        assertThat(events.get(1).payload().toString()).contains("卡片").doesNotContain("null");
        assertThat(events.get(1).payload()).containsEntry("sourceType", "cardList")
                .containsEntry("cardType", "cardList");
    }

    @Test
    void keepsOriginalSourceTypeForSingleDiyCardScene() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"diyCardScene":{"type":"tax"}}
                """);

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(events.getFirst().payload()).containsEntry("sourceType", "diyCardScene")
                .containsEntry("cardType", "diyCardScene")
                .containsEntry("cardSources", List.of("diyCardScene"))
                .doesNotContainKey("contentAgent");
    }

    @Test
    void keepsContentAgentOnlyWhenItAccompaniesDiyCardScene() {
        List<ChatEvent> valued = normalizer.normalize("run1", "session1", """
                message: {"diyCardScene":{"type":"tax"},"contentAgent":"卡片补充内容"}
                """);
        List<ChatEvent> empty = normalizer.normalize("run1", "session1", """
                message: {"diyCardScene":{"type":"tax"},"contentAgent":""}
                """);
        List<ChatEvent> otherCard = normalizer.normalize("run1", "session1", """
                message: {"cardList":[{"title":"卡片"}],"contentAgent":"不应附加"}
                """);

        assertThat(valued).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(valued.getFirst().payload())
                .containsEntry("sourceType", "diyCardScene")
                .containsEntry("cardType", "diyCardScene")
                .containsEntry("cardSources", List.of("diyCardScene"))
                .containsEntry("contentAgent", "卡片补充内容");
        assertThat(empty.getFirst().payload()).containsEntry("contentAgent", "");
        assertThat(otherCard.getFirst().payload()).doesNotContainKey("contentAgent");
    }

    @Test
    void mapsStandaloneContentAgentFramesToRuntimeCardsWithoutParsingTheirContent() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"contentAgent":"<think>"}

                message: {"contentAgent":""}

                message: {"contentAgent":"简单的问候。</think>"}

                """);

        assertThat(events).extracting(ChatEvent::type)
                .containsExactly("runtime.card", "runtime.card", "runtime.card");
        assertThat(events).allSatisfy(event -> assertThat(event.payload())
                .containsEntry("source", "domain-agent")
                .containsEntry("sourceType", "contentAgent")
                .containsEntry("cardType", "contentAgent")
                .containsEntry("cardSources", List.of("contentAgent")));
        assertThat(events).extracting(event -> event.payload().get("contentAgent"))
                .containsExactly("<think>", "", "简单的问候。</think>");
    }

    @Test
    void keepsStandaloneContentAgentSeparateFromThePrecedingCardUrl() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"cardUrl":"https://card","intent":"tax","skillId":"skill-tax"}

                message: {"contentAgent":"卡片内部正文"}

                """);

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.card", "runtime.card");
        assertThat(events.get(0).payload())
                .containsEntry("sourceType", "cardUrl")
                .containsEntry("cardType", "url")
                .doesNotContainKey("contentAgent");
        assertThat(events.get(1).payload())
                .containsEntry("sourceType", "contentAgent")
                .containsEntry("cardType", "contentAgent")
                .containsEntry("contentAgent", "卡片内部正文")
                .doesNotContainKey("cardUrl");
    }

    @Test
    void doesNotTreatNullContentAgentAsACard() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "message: {\"contentAgent\":null}");

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.event");
        assertThat(events.getFirst().payload()).containsEntry("sourceType", "unknown");
    }

    @Test
    void mapsSpecificSceneInfoToCompleteCardAndPreservesAuthorizationBusinessFields() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode scene = root.putArray("specificSceneInfo").addObject();
        scene.put("index", 1);
        scene.put("type", "authorization");
        ObjectNode authority = scene.putArray("data").addObject();
        authority.put("authorityZhName", "审批税审项目关闭");
        authority.put("authorizationAllowedFlag", "K");
        authority.put("authorizationPathStr", "税务/税审与问询");
        authority.put("accessToken", "secret-token");
        ArrayNode scenarios = authority.putArray("scenarioList");
        for (int i = 0; i < 75; i++) {
            scenarios.addObject()
                    .put("displaySeq", i + 1)
                    .put("scenarioZhName", "场景-" + i)
                    .put("documentUrl", "https://example.com/document/" + i);
        }

        List<ChatEvent> events = normalizer.normalize(
                "run1", "session1", "message: " + objectMapper.writeValueAsString(root));

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(events.getFirst().payload())
                .containsEntry("source", "domain-agent")
                .containsEntry("sourceType", "specificSceneInfo")
                .containsEntry("cardType", "specificSceneInfo")
                .containsEntry("cardSources", List.of("specificSceneInfo"));
        List<?> normalizedScenes = (List<?>) events.getFirst().payload().get("specificSceneInfo");
        Map<?, ?> normalizedScene = asMap(normalizedScenes.getFirst());
        Map<?, ?> normalizedAuthority = asMap(((List<?>) normalizedScene.get("data")).getFirst());
        assertThat(normalizedAuthority.get("authorizationAllowedFlag")).isEqualTo("K");
        assertThat(normalizedAuthority.get("authorizationPathStr")).isEqualTo("税务/税审与问询");
        assertThat(normalizedAuthority.get("accessToken")).isEqualTo("[REDACTED]");
        assertThat((List<?>) normalizedAuthority.get("scenarioList"))
                .hasSize(75)
                .noneMatch("[TRUNCATED]"::equals);
    }

    @Test
    void buffersSplitSpecificSceneInfoAndEmitsOneCompleteCard() {
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();

        List<ChatEvent> first = normalizer.normalize("run1", "session1",
                "message: {\"specificSceneInfo\":[{\"type\":\"author", state);
        List<ChatEvent> second = normalizer.normalize("run1", "session1",
                "ization\",\"data\":[{\"authorityZhName\":\"税审审批\"}]}]}", state);

        assertThat(first).isEmpty();
        assertThat(second).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(second.getFirst().payload())
                .containsEntry("sourceType", "specificSceneInfo")
                .containsEntry("cardType", "specificSceneInfo")
                .doesNotContainKeys("fragment", "itemId", "delta", "complete");
        assertThat(second.getFirst().payload().get("specificSceneInfo")).asString()
                .contains("authorization", "税审审批");
    }

    @Test
    void mapsOpenCardToRuntimeCard() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"openCard":"N","intent":"CreditSales","skillId":"skill-credit"}
                """);

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(events.getFirst().payload()).containsEntry("sourceType", "openCard")
                .containsEntry("cardType", "openCard")
                .containsEntry("cardSources", List.of("openCard"))
                .containsEntry("openCard", "N")
                .containsEntry("intent", "CreditSales")
                .containsEntry("domainAgentId", "skill-credit");
    }

    @Test
    void preservesCompleteRecommendedQuestionsInOpenCardPayload() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                data: {"recommendedQuestions":[{"query":"请展开下一个印章的审核结果？","id":1,\
                "type":"1","languageCode":"zh_CN","metadata":{"scene":"seal","apiToken":"secret"},\
                "extension":{"copyable":true}},{"query":"请展开下一个文件的审核结果？","id":2,\
                "type":"1","languageCode":"zh_CN","metadata":{}}],"openCard":"Y"}
                """);

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(events.getFirst().payload())
                .containsEntry("sourceType", "openCard")
                .containsEntry("cardType", "openCard")
                .containsEntry("cardSources", List.of("openCard"))
                .containsEntry("openCard", "Y")
                .containsEntry("recommendedQuestions", List.of(
                        Map.of(
                                "query", "请展开下一个印章的审核结果？",
                                "id", 1,
                                "type", "1",
                                "languageCode", "zh_CN",
                                "metadata", Map.of("scene", "seal", "apiToken", "[REDACTED]"),
                                "extension", Map.of("copyable", true)
                        ),
                        Map.of(
                                "query", "请展开下一个文件的审核结果？",
                                "id", 2,
                                "type", "1",
                                "languageCode", "zh_CN",
                                "metadata", Map.of()
                        )
                ));
    }

    @Test
    void keepsDefensiveMixedCardMappingForUnexpectedCombinedFrame() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"diyCardScene":{"type":"tax"},"cardList":[{"title":"卡片"}]}
                """);

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(events.getFirst().payload()).containsEntry("sourceType", "domain-agent-card")
                .containsEntry("cardType", "mixed")
                .containsEntry("cardSources", List.of("diyCardScene", "cardList"));
    }

    @Test
    void keepsDefensiveMixedCardMappingWhenOpenCardCombinesWithOtherCardFields() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"cardUrl":"https://card","openCard":"N","specificSceneInfo":[]}
                """);

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(events.getFirst().payload()).containsEntry("sourceType", "domain-agent-card")
                .containsEntry("cardType", "mixed")
                .containsEntry("cardSources", List.of("cardUrl", "openCard", "specificSceneInfo"))
                .containsEntry("cardUrl", "https://card")
                .containsEntry("openCard", "N")
                .containsEntry("specificSceneInfo", List.of());
    }

    @Test
    void buffersSplitOpenCardWithoutInvalidJson() {
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();

        List<ChatEvent> first = normalizer.normalize("run1", "session1",
                "message: {\"openCard\":\"", state);
        List<ChatEvent> second = normalizer.normalize("run1", "session1",
                "N\"}", state);

        assertThat(first).isEmpty();
        assertThat(second).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(second.getFirst().payload()).containsEntry("sourceType", "openCard")
                .containsEntry("openCard", "N")
                .doesNotContainKeys("fragment", "itemId", "delta", "complete");
    }

    @Test
    void keepsDocumentedThinkingFrameRealtimeMappingUnchanged() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "data: {\"think_state\":\"start\",\"think_content\":\"分析过程\"}");

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.event");
        assertThat(events.getFirst().payload())
                .containsEntry("source", "domain-agent")
                .containsEntry("sourceType", "unknown");
        Map<?, ?> sourcePayload = asMap(events.getFirst().payload().get("sourcePayload"));
        Map<?, ?> frame = asMap(sourcePayload.get("sourcePayload"));
        assertThat(frame.get("think_state")).isEqualTo("start");
        assertThat(frame.get("think_content")).isEqualTo("分析过程");
    }

    @Test
    void splitsThinkContentFromAnswerContent() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "message: {\"content\":\"<think>分析过程</think>最终答案\"}");

        assertThat(events).extracting(ChatEvent::type).containsExactly(
                "runtime.thinking",
                "runtime.thinking",
                "runtime.thinking",
                "message.delta"
        );
        assertThat(events.get(1).payload()).containsEntry("text", "分析过程");
        assertThat(events.get(3).payload()).containsEntry("delta", "最终答案");
    }

    @Test
    void keepsThinkStateAcrossChunks() {
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();

        List<ChatEvent> first = normalizer.normalize("run1", "session1",
                "message: {\"content\":\"<think>分析\"}", state);
        List<ChatEvent> second = normalizer.normalize("run1", "session1",
                "message: {\"content\":\"过程</think>答案\",\"endFlag\":true}", state);

        assertThat(first).extracting(ChatEvent::type).containsExactly("runtime.thinking", "runtime.thinking");
        assertThat(second).extracting(ChatEvent::type).containsExactly(
                "runtime.thinking",
                "runtime.thinking",
                "message.delta",
                "message.completed"
        );
        assertThat(first.get(1).payload()).containsEntry("text", "分析");
        assertThat(second.get(0).payload()).containsEntry("text", "过程");
        assertThat(second.get(2).payload()).containsEntry("delta", "答案");
    }

    private Map<?, ?> asMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<?, ?>) value;
    }
}
